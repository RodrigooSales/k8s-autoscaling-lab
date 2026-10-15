package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
)

type cpuAdapter struct {
	state adaptationState
}

func (adapter cpuAdapter) adapt(ctx context.Context, multiplier float64, maximum int64) (map[string]any, error) {
	if math.IsNaN(multiplier) || math.IsInf(multiplier, 0) {
		return nil, fmt.Errorf("cpu_multiplier must be finite")
	}
	deployment, err := adapter.state.deployment(ctx)
	if err != nil {
		return nil, err
	}
	if rolloutInProgress(deployment) {
		return map[string]any{"result": "skip"}, nil
	}
	initial, err := adapter.state.initialMCPU(ctx, deployment)
	if err != nil {
		return nil, err
	}
	pods, err := adapter.state.pods(ctx, deployment)
	if err != nil {
		return nil, err
	}
	if len(pods) == 0 {
		return nil, fmt.Errorf("no Pods found for Deployment %s/%s", adapter.state.namespace, adapter.state.deploymentName)
	}
	current, err := currentPodMCPU(pods)
	if err != nil {
		return nil, err
	}
	scaled := float64(current) * multiplier
	if math.IsNaN(scaled) || math.IsInf(scaled, 0) || scaled > math.MaxInt64 || scaled < math.MinInt64 {
		return nil, fmt.Errorf("scaled CPU is outside the supported range")
	}
	newCPU := int64(math.RoundToEven(scaled))
	if newCPU > maximum {
		newCPU = maximum
	}
	if newCPU < initial {
		newCPU = initial
	}
	body, err := cpuResizePatch(newCPU)
	if err != nil {
		return nil, err
	}
	for _, pod := range pods {
		_, err := adapter.state.client.Resource(podsResource).
			Namespace(adapter.state.namespace).
			Patch(ctx, pod.Name, types.StrategicMergePatchType, body, metav1.PatchOptions{}, "resize")
		if err != nil {
			return nil, fmt.Errorf("failed to resize Pod %s/%s: %w", adapter.state.namespace, pod.Name, err)
		}
	}
	return map[string]any{"cpu": newCPU}, nil
}

func cpuResizePatch(cpu int64) ([]byte, error) {
	containers := []any{
		map[string]any{"name": "znn", "resources": map[string]any{"limits": map[string]string{"cpu": fmt.Sprintf("%dm", cpu)}}},
		map[string]any{"name": "nginx", "resources": map[string]any{"limits": map[string]string{"cpu": fmt.Sprintf("%dm", cpu)}}},
	}
	return json.Marshal(map[string]any{"spec": map[string]any{"containers": containers}})
}
