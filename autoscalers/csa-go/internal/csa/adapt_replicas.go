package csa

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/big"

	appsv1 "k8s.io/api/apps/v1"
)

func runAdaptReplicas(
	input []byte,
	stdout, stderr io.Writer,
	logPath string,
	apiFactory func() (kubernetesAPI, error),
) int {
	logger, err := newAdapterLogger("adapt_repl", logPath, stderr)
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
	resource, ok := spec["resource"].(map[string]any)
	if !ok {
		resource = map[string]any{}
	}
	metadata, ok := resource["metadata"].(map[string]any)
	if !ok {
		metadata = map[string]any{}
	}
	name, nameOK := metadata["name"].(string)
	namespace, namespaceOK := metadata["namespace"].(string)
	if !nameOK || name == "" || !namespaceOK || namespace == "" {
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
	_ = logger.info("Starting adapt_replicas script")

	parameters := map[string]any{}
	if evaluation, ok := spec["evaluation"].(map[string]any); ok {
		if value, ok := evaluation["parameters"].(map[string]any); ok {
			parameters = value
		}
	}
	replicas, ok := pythonInteger(parameters["replicas"])
	if !ok {
		_ = logger.error("Parameters must include integer 'replicas'")
		return 0
	}
	_ = logger.info("Scaling to " + pythonIntegerString(replicas) + " replicas")

	body, err := deploymentPatchBody(deployment.object, replicas)
	if err != nil {
		_ = logger.error(fmt.Sprintf("Failed to encode Deployment %s/%s patch: %v", namespace, name, err))
		return 0
	}
	if err := api.patchDeployment(context.Background(), name, namespace, body); err != nil {
		_ = logger.error(fmt.Sprintf("Failed to patch Deployment %s/%s: %v", namespace, name, err))
		return writeJSONResult(stdout, map[string]any{"result": "error"})
	}
	return writeJSONResult(stdout, map[string]any{"replicas": replicas})
}

func decodeAdaptationSpec(input []byte) (map[string]any, error) {
	decoder := json.NewDecoder(bytes.NewReader(input))
	decoder.UseNumber()
	var spec map[string]any
	if err := decoder.Decode(&spec); err != nil {
		return nil, err
	}
	if spec == nil {
		return nil, fmt.Errorf("JSON input must be an object")
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		if err == nil {
			return nil, fmt.Errorf("unexpected data after JSON input")
		}
		return nil, err
	}
	return spec, nil
}

func pythonInteger(value any) (any, bool) {
	switch value := value.(type) {
	case bool:
		return value, true
	case json.Number:
		integer, ok := new(big.Int).SetString(value.String(), 10)
		if !ok {
			return nil, false
		}
		return json.Number(integer.String()), true
	default:
		return nil, false
	}
}

func pythonIntegerString(value any) string {
	if boolean, ok := value.(bool); ok {
		if boolean {
			return "True"
		}
		return "False"
	}
	return fmt.Sprint(value)
}

func deploymentPatchBody(deployment *appsv1.Deployment, replicas any) ([]byte, error) {
	encoded, err := json.Marshal(deployment)
	if err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(bytes.NewReader(encoded))
	decoder.UseNumber()
	var body map[string]any
	if err := decoder.Decode(&body); err != nil {
		return nil, err
	}
	spec, ok := body["spec"].(map[string]any)
	if !ok {
		spec = map[string]any{}
		body["spec"] = spec
	}
	spec["replicas"] = replicas
	return json.Marshal(body)
}

func writeJSONResult(stdout io.Writer, value map[string]any) int {
	output, err := json.Marshal(value)
	if err != nil {
		return 1
	}
	if _, err := stdout.Write(output); err != nil {
		return 1
	}
	return 0
}
