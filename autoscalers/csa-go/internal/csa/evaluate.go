package csa

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"math/big"
	"strconv"
	"strings"
)

const (
	strategyReplicas = "adapt_replicas"
	strategyCPU      = "adapt_cpu"
	strategyTag      = "adapt_tag"
	parameterCPU     = "cpu_multiplier"
	parameterTagUp   = "tag_up"
	parameterUpdate  = "update_cpu"
)

type evaluationInput struct {
	currentReplicas float64
	currentValue    *big.Int
	scaledTarget    *big.Int
	rate            float64
}

func evaluateDecision(spec, config map[string]any, currentMCPU, initialMCPU int64) (map[string]any, error) {
	input, err := parseEvaluationInput(spec)
	if err != nil {
		return nil, err
	}
	return decideEvaluation(input, config, currentMCPU, initialMCPU)
}

func parseEvaluationInput(spec map[string]any) (evaluationInput, error) {
	metric, err := firstMetricPayload(spec)
	if err != nil {
		return evaluationInput{}, err
	}
	return evaluationInputFromMetric(spec, metric)
}

func evaluationInputFromMetric(spec, metric map[string]any) (evaluationInput, error) {
	currentValue, err := metricQuantity(metric["current_value"])
	if err != nil {
		return evaluationInput{}, fmt.Errorf("invalid current_value: %w", err)
	}
	targetValue, err := metricQuantity(metric["target_value"])
	if err != nil {
		return evaluationInput{}, fmt.Errorf("invalid target_value: %w", err)
	}
	scaledTarget := new(big.Int).Mul(targetValue, big.NewInt(1000))
	if scaledTarget.Sign() == 0 {
		return evaluationInput{}, fmt.Errorf("target metric is zero")
	}
	currentFloat, _ := currentValue.Float64()
	targetFloat, _ := scaledTarget.Float64()
	rate := currentFloat / targetFloat

	resource, err := mapField(spec, "resource")
	if err != nil {
		return evaluationInput{}, err
	}
	resourceSpec, err := mapField(resource, "spec")
	if err != nil {
		return evaluationInput{}, err
	}
	currentReplicas, err := numberAsFloat64(resourceSpec["replicas"])
	if err != nil {
		return evaluationInput{}, fmt.Errorf("invalid resource.spec.replicas: %w", err)
	}
	return evaluationInput{currentReplicas: currentReplicas, currentValue: currentValue, scaledTarget: scaledTarget, rate: rate}, nil
}

func decideEvaluation(input evaluationInput, config map[string]any, currentMCPU, initialMCPU int64) (map[string]any, error) {
	enabledStrategies, found := config["enabled_strategies"]
	if !found {
		enabledStrategies = strategyReplicas
	}
	minReplicas, err := configInteger(config, "minReplicas", 1)
	if err != nil {
		return nil, err
	}
	maxReplicas, err := configInteger(config, "maxReplicas", 10)
	if err != nil {
		return nil, err
	}
	maxCPU, err := configInteger(config, "maxCPU", 1000)
	if err != nil {
		return nil, err
	}
	desiredReplicas := int64(math.Ceil(input.currentReplicas * input.rate))

	if input.rate >= 0.95 {
		enabled, err := strategyEnabled(enabledStrategies, strategyReplicas)
		if err != nil {
			return nil, err
		}
		if enabled && input.currentReplicas < float64(maxReplicas) {
			if desiredReplicas > maxReplicas {
				desiredReplicas = maxReplicas
			}
			return map[string]any{"strategy": strategyReplicas, "parameters": map[string]any{"replicas": desiredReplicas}}, nil
		}

		enabled, err = strategyEnabled(enabledStrategies, strategyCPU)
		if err != nil {
			return nil, err
		}
		if enabled && currentMCPU < maxCPU {
			return map[string]any{"strategy": strategyCPU, "parameters": map[string]any{parameterCPU: input.rate}}, nil
		}

		enabled, err = strategyEnabled(enabledStrategies, strategyTag)
		if err != nil {
			return nil, err
		}
		if enabled {
			updateCPU, err := strategyEnabled(enabledStrategies, strategyCPU)
			if err != nil {
				return nil, err
			}
			return map[string]any{
				"strategy":   strategyTag,
				"parameters": map[string]any{parameterTagUp: false, parameterUpdate: updateCPU},
			}, nil
		}
	}

	if input.rate < 0.90 {
		enabled, err := strategyEnabled(enabledStrategies, strategyReplicas)
		if err != nil {
			return nil, err
		}
		if enabled && input.currentReplicas > float64(minReplicas) {
			if desiredReplicas < minReplicas {
				desiredReplicas = minReplicas
			}
			return map[string]any{"strategy": strategyReplicas, "parameters": map[string]any{"replicas": desiredReplicas}}, nil
		}

		enabled, err = strategyEnabled(enabledStrategies, strategyCPU)
		if err != nil {
			return nil, err
		}
		if enabled && currentMCPU > initialMCPU {
			return map[string]any{"strategy": strategyCPU, "parameters": map[string]any{parameterCPU: input.rate}}, nil
		}

		enabled, err = strategyEnabled(enabledStrategies, strategyTag)
		if err != nil {
			return nil, err
		}
		if enabled {
			return map[string]any{"strategy": strategyTag, "parameters": map[string]any{parameterTagUp: true}}, nil
		}
	}
	return nil, nil
}

func firstMetricPayload(spec map[string]any) (map[string]any, error) {
	metrics, ok := spec["metrics"].([]any)
	if !ok || len(metrics) == 0 {
		return nil, fmt.Errorf("missing metrics[0].value payload")
	}
	first, ok := metrics[0].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("metrics[0] must be an object")
	}
	value, ok := first["value"].(string)
	if !ok || value == "" {
		return nil, fmt.Errorf("missing metrics[0].value payload")
	}
	decoder := json.NewDecoder(bytes.NewBufferString(value))
	decoder.UseNumber()
	var metric map[string]any
	if err := decoder.Decode(&metric); err != nil {
		return nil, fmt.Errorf("invalid metrics[0].value payload: %w", err)
	}
	if metric == nil {
		return nil, fmt.Errorf("metrics[0].value payload must be an object")
	}
	return metric, nil
}

func metricQuantity(value any) (*big.Int, error) {
	switch value := value.(type) {
	case string:
		return parseQuantityInteger(value)
	case json.Number:
		return parseQuantityInteger(value.String())
	case int:
		return big.NewInt(int64(value)), nil
	case int64:
		return big.NewInt(value), nil
	case float64:
		return parseQuantityInteger(strconv.FormatFloat(value, 'f', -1, 64))
	default:
		return nil, fmt.Errorf("unsupported quantity type %T", value)
	}
}

func strategyEnabled(configured any, strategy string) (bool, error) {
	switch configured := configured.(type) {
	case string:
		return strings.Contains(configured, strategy), nil
	case []any:
		for _, value := range configured {
			if value == strategy {
				return true, nil
			}
		}
		return false, nil
	case map[string]any:
		_, exists := configured[strategy]
		return exists, nil
	default:
		return false, fmt.Errorf("enabled_strategies is not iterable")
	}
}

func configInteger(config map[string]any, key string, defaultValue int64) (int64, error) {
	value, exists := config[key]
	if !exists || value == nil {
		return defaultValue, nil
	}
	parsed, err := numberAsInt64(value)
	if err != nil {
		return 0, fmt.Errorf("invalid config value %s: %w", key, err)
	}
	return parsed, nil
}

func numberAsInt64(value any) (int64, error) {
	switch value := value.(type) {
	case int:
		return int64(value), nil
	case int32:
		return int64(value), nil
	case int64:
		return value, nil
	case json.Number:
		return value.Int64()
	case float64:
		if math.Trunc(value) != value || value > math.MaxInt64 || value < math.MinInt64 {
			return 0, fmt.Errorf("not an integer: %v", value)
		}
		return int64(value), nil
	case string:
		return strconv.ParseInt(value, 10, 64)
	case bool:
		if value {
			return 1, nil
		}
		return 0, nil
	default:
		return 0, fmt.Errorf("unsupported number type %T", value)
	}
}

func numberAsFloat64(value any) (float64, error) {
	switch value := value.(type) {
	case int:
		return float64(value), nil
	case int64:
		return float64(value), nil
	case json.Number:
		return value.Float64()
	case float64:
		return value, nil
	case bool:
		if value {
			return 1, nil
		}
		return 0, nil
	default:
		return 0, fmt.Errorf("unsupported number type %T", value)
	}
}

func mapField(source map[string]any, key string) (map[string]any, error) {
	value, ok := source[key].(map[string]any)
	if !ok {
		return nil, fmt.Errorf("missing or invalid %s object", key)
	}
	return value, nil
}
