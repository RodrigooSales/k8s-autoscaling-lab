package main

import (
	"encoding/json"
	"fmt"
)

type metricResultJSON struct {
	CurrentReplicas json.RawMessage `json:"current_replicas"`
	TargetValue     json.RawMessage `json:"target_value"`
	CurrentValue    json.RawMessage `json:"current_value"`
}

func metricResult(input []byte) ([]byte, error) {
	var spec map[string]json.RawMessage
	if err := json.Unmarshal(input, &spec); err != nil {
		return nil, fmt.Errorf("invalid runtime metric payload: %w", err)
	}
	resource, err := objectField(spec, "resource")
	if err != nil {
		return nil, err
	}
	resourceSpec, err := objectField(resource, "spec")
	if err != nil {
		return nil, err
	}
	replicas, err := valueField(resourceSpec, "replicas")
	if err != nil {
		return nil, err
	}

	kubernetesMetricsRaw, err := valueField(spec, "kubernetesMetrics")
	if err != nil {
		return nil, err
	}
	var kubernetesMetrics []json.RawMessage
	if err := json.Unmarshal(kubernetesMetricsRaw, &kubernetesMetrics); err != nil || len(kubernetesMetrics) == 0 {
		return nil, fmt.Errorf("kubernetesMetrics must contain at least one metric")
	}
	firstMetric, err := decodeObject(kubernetesMetrics[0], "kubernetesMetrics[0]")
	if err != nil {
		return nil, err
	}
	metricSpec, err := objectField(firstMetric, "spec")
	if err != nil {
		return nil, err
	}
	specExternal, err := objectField(metricSpec, "external")
	if err != nil {
		return nil, err
	}
	target, err := objectField(specExternal, "target")
	if err != nil {
		return nil, err
	}
	targetValue, err := valueField(target, "value")
	if err != nil {
		return nil, err
	}
	external, err := objectField(firstMetric, "external")
	if err != nil {
		return nil, err
	}
	current, err := objectField(external, "current")
	if err != nil {
		return nil, err
	}
	currentValue, err := valueField(current, "value")
	if err != nil {
		return nil, err
	}

	return json.Marshal(metricResultJSON{
		CurrentReplicas: replicas,
		TargetValue:     targetValue,
		CurrentValue:    currentValue,
	})
}

func objectField(object map[string]json.RawMessage, name string) (map[string]json.RawMessage, error) {
	value, err := valueField(object, name)
	if err != nil {
		return nil, err
	}
	return decodeObject(value, name)
}

func valueField(object map[string]json.RawMessage, name string) (json.RawMessage, error) {
	value, ok := object[name]
	if !ok || string(value) == "null" {
		return nil, fmt.Errorf("missing field %q", name)
	}
	return value, nil
}

func decodeObject(raw json.RawMessage, name string) (map[string]json.RawMessage, error) {
	var object map[string]json.RawMessage
	if err := json.Unmarshal(raw, &object); err != nil || object == nil {
		return nil, fmt.Errorf("field %q must be an object", name)
	}
	return object, nil
}
