package main

import (
	"fmt"
	"math"
	"slices"
)

const (
	strategyReplicas = "adapt_replicas"
	strategyCPU      = "adapt_cpu"
	strategyTag      = "adapt_tag"
)

type evaluationInput struct {
	currentReplicas    int32
	currentMetricValue int64
	targetMetricValue  int64
	currentMCPU        int64
	initialMCPU        int64
	minReplicas        int32
	maxReplicas        int32
	maxCPU             int64
	enabledStrategies  []string
}

type evaluationDecision struct {
	Strategy   string         `json:"strategy"`
	Parameters map[string]any `json:"parameters"`
}

func evaluate(input evaluationInput) (*evaluationDecision, error) {
	if input.targetMetricValue == 0 {
		return nil, fmt.Errorf("target metric is zero")
	}

	rate := float64(input.currentMetricValue) / (float64(input.targetMetricValue) * 1000)
	if rate >= 0.95 {
		if slices.Contains(input.enabledStrategies, strategyReplicas) && input.currentReplicas < input.maxReplicas {
			return replicaDecision(input, rate, true), nil
		}
		if slices.Contains(input.enabledStrategies, strategyCPU) && input.currentMCPU < input.maxCPU {
			return &evaluationDecision{
				Strategy:   strategyCPU,
				Parameters: map[string]any{"cpu_multiplier": rate},
			}, nil
		}
		if slices.Contains(input.enabledStrategies, strategyTag) {
			return &evaluationDecision{
				Strategy:   strategyTag,
				Parameters: map[string]any{"tag_up": false, "update_cpu": slices.Contains(input.enabledStrategies, strategyCPU)},
			}, nil
		}
	}

	if rate < 0.90 {
		if slices.Contains(input.enabledStrategies, strategyReplicas) && input.currentReplicas > input.minReplicas {
			return replicaDecision(input, rate, false), nil
		}
		if slices.Contains(input.enabledStrategies, strategyCPU) && input.currentMCPU > input.initialMCPU {
			return &evaluationDecision{
				Strategy:   strategyCPU,
				Parameters: map[string]any{"cpu_multiplier": rate},
			}, nil
		}
		if slices.Contains(input.enabledStrategies, strategyTag) {
			return &evaluationDecision{
				Strategy:   strategyTag,
				Parameters: map[string]any{"tag_up": true},
			}, nil
		}
	}
	return nil, nil
}

func replicaDecision(input evaluationInput, rate float64, capAtMax bool) *evaluationDecision {
	desiredReplicas := math.Ceil(float64(input.currentReplicas) * rate)
	if capAtMax {
		desiredReplicas = math.Min(desiredReplicas, float64(input.maxReplicas))
	} else {
		desiredReplicas = math.Max(desiredReplicas, float64(input.minReplicas))
	}
	return &evaluationDecision{
		Strategy:   strategyReplicas,
		Parameters: map[string]any{"replicas": int32(desiredReplicas)},
	}
}
