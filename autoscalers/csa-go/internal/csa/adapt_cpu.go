package csa

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"math/big"

	appsv1 "k8s.io/api/apps/v1"
)

func runAdaptCPU(
	input []byte,
	stdout, stderr io.Writer,
	configPath, logPath string,
	apiFactory func() (kubernetesAPI, error),
	env func(string) string,
) int {
	logger, err := newAdapterLogger("adapt_cpu", logPath, stderr)
	if err != nil {
		fmt.Fprintf(stderr, "failed to create logger: %v\n", err)
		return 1
	}
	defer logger.close()

	spec, err := decodeAdaptationSpec(input)
	if err != nil {
		_ = logger.error("Invalid JSON on stdin: " + err.Error())
		return 0
	}
	name, namespace, ok := adaptationIdentity(spec)
	if !ok {
		_ = logger.error("Spec must include resource.metadata.name and resource.metadata.namespace")
		return 0
	}
	api, err := apiFactory()
	if err != nil {
		_ = logger.error(fmt.Sprintf("Failed to load in-cluster config: %v", err))
		return 0
	}
	deployment, err := api.readDeployment(context.Background(), name, namespace)
	if err != nil {
		_ = logger.error(fmt.Sprintf("Failed to read Deployment %s/%s: %v", namespace, name, err))
		return 0
	}
	if deployment == nil || deployment.object == nil {
		_ = logger.error(fmt.Sprintf("Deployment %s/%s not found", namespace, name))
		return 0
	}

	parameters := adaptationParameters(spec)
	multiplierValue := parameters[parameterCPU]
	_ = logger.info(fmt.Sprintf("adapt_cpu for rate %s", pythonValueString(multiplierValue)))
	multiplier, ok := pythonFloat(multiplierValue)
	if !ok {
		_ = logger.error(fmt.Sprintf("Parameter '%s' must be a number; it's %s", parameterCPU, pythonValueString(multiplierValue)))
		return writeJSONResult(stdout, map[string]any{"result": "error"})
	}
	if rolloutInProgress(deployment.object) {
		_ = logger.info("Rollout in progress, skipping deployment patch")
		return writeJSONResult(stdout, map[string]any{"result": "skip"})
	}

	config, err := loadConfig(configPath, stderr)
	if err != nil {
		_ = logger.error(err.Error())
		return 1
	}
	if config == nil {
		_ = logger.error("Could not parse config")
		return writeJSONResult(stdout, map[string]any{"result": "error"})
	}
	maxCPU := int64(1000)
	if configured, exists := config["maxCPU"]; exists {
		maxCPU, err = numberAsInt64(configured)
		if err != nil {
			_ = logger.error(fmt.Sprintf("invalid maxCPU: %v", err))
			return 1
		}
	}

	store := &initialDataStore{api: api, logger: logger, env: env}
	initialCPU, err := store.getStoredCPU()
	if err != nil {
		return adaptationRuntimeError(logger, err)
	}
	_ = logger.info(fmt.Sprintf("Read initial_mcpu_data %d.", initialCPU))
	if initialCPU == 0 {
		specCPU, err := deploymentSpecMCPU(deployment)
		if err != nil {
			return adaptationRuntimeError(logger, err)
		}
		if !pythonTruthy(specCPU) {
			_ = logger.error("No limits found in the containers specs!")
			return writeJSONResult(stdout, map[string]any{"result": "error"})
		}
		initialCPU, err = numberAsInt64(specCPU)
		if err != nil {
			return adaptationRuntimeError(logger, err)
		}
		_ = logger.info(fmt.Sprintf("Read spec_mcpu %d", initialCPU))
		_ = logger.info(fmt.Sprintf("Storing initial cpu limit %d", initialCPU))
		if err := store.storeCPU(initialCPU); err != nil {
			return adaptationRuntimeError(logger, err)
		}
	}

	selector, err := deploymentLabelSelector(deployment.object)
	if err != nil {
		return adaptationRuntimeError(logger, err)
	}
	pods, err := api.listPods(context.Background(), namespace, selector)
	if err != nil {
		return adaptationRuntimeError(logger, err)
	}
	if pods == nil || len(pods.items) == 0 {
		_ = logger.error(fmt.Sprintf("Could not find pods for deployment %s in namespace %s", name, namespace))
		return writeJSONResult(stdout, map[string]any{"result": "error"})
	}

	currentPods, err := api.listPods(context.Background(), namespace, selector)
	if err != nil {
		return adaptationRuntimeError(logger, err)
	}
	currentCPU, found, err := currentPodMCPU(currentPods)
	if err != nil {
		return adaptationRuntimeError(logger, err)
	}
	if !found || currentCPU == 0 {
		_ = logger.error("Current CPU limit not found or unparsable")
		return writeJSONResult(stdout, map[string]any{"result": "error"})
	}

	roundedCPU, err := scaledCPUMilli(currentCPU, multiplier)
	if err != nil {
		return adaptationRuntimeError(logger, err)
	}
	_ = logger.info(fmt.Sprintf("Calculated new_mcpu %s", roundedCPU))
	newCPU, cappedMaximum, cappedInitial := clampCPUMilli(roundedCPU, initialCPU, maxCPU)
	if cappedMaximum {
		_ = logger.info(fmt.Sprintf("new_mcpu capped to %d", maxCPU))
	}
	if cappedInitial {
		_ = logger.info(fmt.Sprintf("new_mcpu capped to %d", initialCPU))
	}
	body, err := cpuResizePatch(newCPU)
	if err != nil {
		return adaptationRuntimeError(logger, err)
	}
	for _, pod := range pods.items {
		if err := api.resizePod(context.Background(), pod.object.Name, namespace, body); err != nil {
			_ = logger.error(fmt.Sprintf("Failed to resize pod %s/%s: %v", namespace, pod.object.Name, err))
			return writeJSONResult(stdout, map[string]any{"result": "error"})
		}
	}
	_ = logger.info(fmt.Sprintf("Scaled cpu to %d", newCPU))
	return writeJSONResult(stdout, map[string]any{"cpu": newCPU})
}

func adjustedCPUMilli(current int64, multiplier float64, initial, maximum int64) (int64, error) {
	roundedCPU, err := scaledCPUMilli(current, multiplier)
	if err != nil {
		return 0, err
	}
	newCPU, _, _ := clampCPUMilli(roundedCPU, initial, maximum)
	return newCPU, nil
}

func scaledCPUMilli(current int64, multiplier float64) (*big.Int, error) {
	scaled := float64(current) * multiplier
	if math.IsNaN(scaled) || math.IsInf(scaled, 0) {
		return nil, fmt.Errorf("scaled CPU is not finite")
	}
	rounded := math.RoundToEven(scaled)
	value, _ := new(big.Float).SetFloat64(rounded).Int(nil)
	return value, nil
}

func clampCPUMilli(rounded *big.Int, initial, maximum int64) (int64, bool, bool) {
	value := new(big.Int).Set(rounded)
	cappedMaximum := false
	if value.Cmp(big.NewInt(maximum)) > 0 {
		value.SetInt64(maximum)
		cappedMaximum = true
	}
	cappedInitial := false
	if value.Cmp(big.NewInt(initial)) < 0 {
		value.SetInt64(initial)
		cappedInitial = true
	}
	return value.Int64(), cappedMaximum, cappedInitial
}

func cpuResizePatch(cpu int64) ([]byte, error) {
	containers := []any{
		map[string]any{"name": "znn", "resources": map[string]any{"limits": map[string]string{"cpu": formatCPUMilli(cpu)}}},
		map[string]any{"name": "nginx", "resources": map[string]any{"limits": map[string]string{"cpu": formatCPUMilli(cpu)}}},
	}
	return json.Marshal(map[string]any{"spec": map[string]any{"containers": containers}})
}

func formatCPUMilli(cpu int64) string {
	return fmt.Sprintf("%dm", cpu)
}

func pythonFloat(value any) (float64, bool) {
	switch value := value.(type) {
	case json.Number:
		number, err := value.Float64()
		return number, err == nil && !math.IsInf(number, 0) && !math.IsNaN(number)
	case bool:
		if value {
			return 1, true
		}
		return 0, true
	default:
		return 0, false
	}
}

func pythonValueString(value any) string {
	if value == nil {
		return "None"
	}
	if boolean, ok := value.(bool); ok {
		if boolean {
			return "True"
		}
		return "False"
	}
	return fmt.Sprint(value)
}

func rolloutInProgress(deployment *appsv1.Deployment) bool {
	desired := int32(0)
	if deployment.Spec.Replicas != nil {
		desired = *deployment.Spec.Replicas
	}
	status := deployment.Status
	return !(status.ObservedGeneration >= deployment.Generation && status.UpdatedReplicas == desired && status.AvailableReplicas == desired)
}

func adaptationIdentity(spec map[string]any) (string, string, bool) {
	resource, ok := spec["resource"].(map[string]any)
	if !ok {
		return "", "", false
	}
	metadata, ok := resource["metadata"].(map[string]any)
	if !ok {
		return "", "", false
	}
	name, nameOK := metadata["name"].(string)
	namespace, namespaceOK := metadata["namespace"].(string)
	return name, namespace, nameOK && name != "" && namespaceOK && namespace != ""
}

func adaptationParameters(spec map[string]any) map[string]any {
	if evaluation, ok := spec["evaluation"].(map[string]any); ok {
		if parameters, ok := evaluation["parameters"].(map[string]any); ok {
			return parameters
		}
	}
	return map[string]any{}
}

func adaptationRuntimeError(logger *adapterLogger, err error) int {
	_ = logger.error(err.Error())
	return 1
}
