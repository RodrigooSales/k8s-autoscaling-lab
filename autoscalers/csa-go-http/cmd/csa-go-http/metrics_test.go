package main

import (
	"encoding/json"
	"testing"
)

func TestMetricResultUsesFirstRuntimeMetricAndPreservesValues(t *testing.T) {
	input := []byte(`{"resource":{"spec":{"replicas":2}},"kubernetesMetrics":[{"spec":{"external":{"target":{"value":"1000"}}},"external":{"current":{"value":"950000"}}},{"spec":{"external":{"target":{"value":"2000"}}},"external":{"current":{"value":"999999"}}}]}`)
	result, err := metricResult(input)
	if err != nil {
		t.Fatal(err)
	}
	var actual map[string]any
	if err := json.Unmarshal(result, &actual); err != nil {
		t.Fatal(err)
	}
	if actual["current_replicas"] != float64(2) || actual["target_value"] != "1000" || actual["current_value"] != "950000" {
		t.Errorf("metric result = %s, want values from the first runtime metric", result)
	}
}

func TestMetricResultRejectsMissingRuntimeMetric(t *testing.T) {
	_, err := metricResult([]byte(`{"resource":{"spec":{"replicas":2}},"kubernetesMetrics":[]}`))
	if err == nil {
		t.Fatal("empty Kubernetes metric list was accepted")
	}
}
