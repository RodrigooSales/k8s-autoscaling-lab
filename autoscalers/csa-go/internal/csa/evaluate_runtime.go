package csa

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strings"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
)

type evaluationOutput struct {
	Strategy   string         `json:"strategy"`
	Parameters map[string]any `json:"parameters"`
}

func runEvaluate(
	input []byte,
	stdout, stderr io.Writer,
	configPath, logPath string,
	apiFactory func() (kubernetesAPI, error),
	env func(string) string,
) int {
	logger, err := newAdapterLogger("evaluate", logPath, stderr)
	if err != nil {
		fmt.Fprintf(stderr, "failed to create logger: %v\n", err)
		return 1
	}
	defer logger.close()
	_ = logger.infoAs("eval_main", "evaluate main")

	var spec map[string]any
	decoder := json.NewDecoder(strings.NewReader(string(input)))
	decoder.UseNumber()
	if err := decoder.Decode(&spec); err != nil {
		return evaluateError(stderr, logger, fmt.Errorf("invalid JSON on stdin: %w", err))
	}
	resource, err := mapField(spec, "resource")
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	metadata, err := mapField(resource, "metadata")
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	name, nameOK := metadata["name"].(string)
	namespace, namespaceOK := metadata["namespace"].(string)
	if !nameOK || name == "" || !namespaceOK || namespace == "" {
		return evaluateError(stderr, logger, errors.New("spec must include resource.metadata.name and resource.metadata.namespace"))
	}

	api, err := apiFactory()
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	deployment, err := api.readDeployment(context.Background(), name, namespace)
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	if deployment == nil || deployment.object == nil {
		return evaluateError(stderr, logger, fmt.Errorf("Deployment %s/%s not found", namespace, name))
	}
	_ = logger.info("Starting evaluate script")

	config, err := loadConfig(configPath, stderr)
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	if config == nil {
		_ = logger.error("config is None")
		return 0
	}
	_ = logger.info("  loaded config")
	metric, err := firstMetricPayload(spec)
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	_ = logger.info("  loaded spec data")
	_ = logger.info("  parsed config data")
	evaluation, err := evaluationInputFromMetric(spec, metric)
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	_ = logger.info(fmt.Sprintf("rate %s / %s = %v", evaluation.currentValue, evaluation.scaledTarget, evaluation.rate))
	_ = logger.info(fmt.Sprintf("plan for rate %v; strageies %v", evaluation.rate, configStrategies(config)))

	selector, err := deploymentLabelSelector(deployment.object)
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	pods, err := api.listPods(context.Background(), namespace, selector)
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	currentMCPU, found, err := currentPodMCPU(pods)
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	if !found || currentMCPU == 0 {
		_ = logger.error("Current CPU limit not found or unparsable")
		return 0
	}

	store := &initialDataStore{api: api, logger: logger, env: env}
	initialMCPU, err := store.getStoredCPU()
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	if initialMCPU == 0 {
		specMCPU, err := deploymentSpecMCPU(deployment)
		if err != nil {
			return evaluateError(stderr, logger, err)
		}
		if err := store.storeCPU(specMCPU); err != nil {
			return evaluateError(stderr, logger, err)
		}
		// Keep the evaluator's local baseline at zero until the operator reconciles
		// the Pod annotation into status.initialData.
	}

	result, err := decideEvaluation(evaluation, config, currentMCPU, initialMCPU)
	if err != nil {
		return evaluateError(stderr, logger, err)
	}
	if result == nil {
		_ = logger.info("No adaptation selected")
		return 0
	}
	strategy, _ := result["strategy"].(string)
	parameters, _ := result["parameters"].(map[string]any)
	output, err := json.Marshal(evaluationOutput{Strategy: strategy, Parameters: parameters})
	if err != nil {
		return evaluateError(stderr, logger, fmt.Errorf("failed to encode evaluation result: %w", err))
	}
	_ = logger.info(string(output))
	if _, err := stdout.Write(output); err != nil {
		fmt.Fprintf(stderr, "failed to write stdout: %v\n", err)
		return 1
	}
	return 0
}

func evaluateError(stderr io.Writer, logger *adapterLogger, err error) int {
	_ = logger.error(err.Error())
	fmt.Fprintln(stderr, err)
	return 1
}

func deploymentLabelSelector(deployment *appsv1.Deployment) (string, error) {
	if deployment.Spec.Selector == nil {
		return "", errors.New("Deployment spec.selector is missing")
	}
	labels := deployment.Spec.Selector.MatchLabels
	if len(labels) == 0 {
		return "", nil
	}
	keys := make([]string, 0, len(labels))
	for key := range labels {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	pairs := make([]string, 0, len(keys))
	for _, key := range keys {
		pairs = append(pairs, key+"="+labels[key])
	}
	return strings.Join(pairs, ","), nil
}

func currentPodMCPU(pods *podListSnapshot) (int64, bool, error) {
	if pods == nil || len(pods.items) == 0 {
		return 0, false, nil
	}
	values := make([]int64, 0, len(pods.items))
	for _, pod := range pods.items {
		containers := pod.object.Spec.Containers
		if len(containers) == 0 || !pod.firstContainerResourcesPresent {
			continue
		}
		cpu, exists := containers[0].Resources.Limits[corev1.ResourceCPU]
		if !exists {
			values = append(values, 0)
			continue
		}
		milli, ok := parseCPUToMilli(cpu.String())
		if !ok {
			milli = 0
		}
		values = append(values, milli)
	}
	if len(values) == 0 {
		return 0, false, errors.New("max() arg is an empty sequence")
	}
	maximum := values[0]
	for _, value := range values[1:] {
		if value > maximum {
			maximum = value
		}
	}
	return maximum, true, nil
}

func deploymentSpecMCPU(deployment *deploymentSnapshot) (any, error) {
	containers := deployment.object.Spec.Template.Spec.Containers
	for index, container := range containers {
		if index >= len(deployment.containerResourcesPresent) || !deployment.containerResourcesPresent[index] {
			continue
		}
		cpu, exists := container.Resources.Limits[corev1.ResourceCPU]
		if !exists {
			return int64(0), nil
		}
		milli, ok := parseCPUToMilli(cpu.String())
		if !ok {
			return nil, nil
		}
		return milli, nil
	}
	return nil, nil
}

func configStrategies(config map[string]any) any {
	if strategies, exists := config["enabled_strategies"]; exists {
		return strategies
	}
	return strategyReplicas
}
