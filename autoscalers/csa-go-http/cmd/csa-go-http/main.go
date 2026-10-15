package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math"
	"math/big"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"k8s.io/apimachinery/pkg/api/resource"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/rest"
	"sigs.k8s.io/yaml"
)

const maxCallbackRequestBytes = 1 << 20

type runtimeProfile struct {
	MinReplicas       int32    `json:"minReplicas"`
	MaxReplicas       int32    `json:"maxReplicas"`
	MaxCPU            int64    `json:"maxCPU"`
	EnabledStrategies []string `json:"enabled_strategies"`
}

type adaptationCallbacks struct {
	adaptReplicas func(context.Context, int32) error
	adaptCPU      func(context.Context, float64, int64) (map[string]any, error)
	adaptTag      func(context.Context, bool, bool) (map[string]any, error)
	cpuState      func(context.Context) (int64, int64, error)
}

func main() {
	profilePath := os.Getenv("CSA_PROFILE_PATH")
	if profilePath == "" {
		profilePath = "/config.yaml"
	}
	profile, err := loadRuntimeProfile(profilePath)
	if err != nil {
		log.Fatal(err)
	}

	var clientOnce sync.Once
	var adapter adaptationState
	var replicas replicaAdapter
	var adapterError error
	initializeAdapters := func() error {
		clientOnce.Do(func() {
			namespace, err := requiredEnvironmentValue("CSA_NAMESPACE")
			if err != nil {
				adapterError = err
				return
			}
			csaName, err := requiredEnvironmentValue("CSA_NAME")
			if err != nil {
				adapterError = err
				return
			}
			deploymentName, err := requiredEnvironmentValue("CSA_DEPLOYMENT_NAME")
			if err != nil {
				adapterError = err
				return
			}
			configuration, err := rest.InClusterConfig()
			if err != nil {
				adapterError = fmt.Errorf("failed to load Kubernetes configuration: %w", err)
				return
			}
			kubernetesClient, err := dynamic.NewForConfig(configuration)
			if err != nil {
				adapterError = fmt.Errorf("failed to create Kubernetes client: %w", err)
				return
			}
			adapter = adaptationState{
				client:         kubernetesClient,
				namespace:      namespace,
				csaName:        csaName,
				deploymentName: deploymentName,
			}
			replicas = replicaAdapter{
				client:         kubernetesClient,
				namespace:      namespace,
				deploymentName: deploymentName,
			}
		})
		return adapterError
	}
	callbacks := adaptationCallbacks{
		adaptReplicas: func(ctx context.Context, desired int32) error {
			if err := initializeAdapters(); err != nil {
				return err
			}
			return replicas.adapt(ctx, desired)
		},
		adaptCPU: func(ctx context.Context, multiplier float64, maximum int64) (map[string]any, error) {
			if err := initializeAdapters(); err != nil {
				return nil, err
			}
			return (cpuAdapter{state: adapter}).adapt(ctx, multiplier, maximum)
		},
		adaptTag: func(ctx context.Context, tagUp, updateCPU bool) (map[string]any, error) {
			if err := initializeAdapters(); err != nil {
				return nil, err
			}
			return (tagAdapter{state: adapter}).adapt(ctx, tagUp, updateCPU)
		},
		cpuState: func(ctx context.Context) (int64, int64, error) {
			if err := initializeAdapters(); err != nil {
				return 0, 0, err
			}
			return adapter.cpuState(ctx)
		},
	}

	server := &http.Server{
		Addr:              ":8080",
		Handler:           newHTTPHandler(profile, callbacks),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}

func newHTTPHandler(profile runtimeProfile, callbacks adaptationCallbacks) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /health", health)
	mux.HandleFunc("POST /metric", func(response http.ResponseWriter, request *http.Request) {
		input, err := readCallbackRequest(request)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		result, err := metricResult(input)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		writeCallbackJSON(response, result)
	})
	mux.HandleFunc("POST /evaluate", func(response http.ResponseWriter, request *http.Request) {
		input, err := readCallbackRequest(request)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		var currentCPU, initialCPU int64
		if strategyEnabled(profile.EnabledStrategies, strategyCPU) {
			if callbacks.cpuState == nil {
				writeCallbackError(response, http.StatusInternalServerError, fmt.Errorf("CPU state callback is not configured"))
				return
			}
			currentCPU, initialCPU, err = callbacks.cpuState(request.Context())
			if err != nil {
				writeCallbackError(response, http.StatusInternalServerError, err)
				return
			}
		}
		result, err := evaluationResultWithCPU(input, profile, currentCPU, initialCPU)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		if len(result) == 0 {
			response.WriteHeader(http.StatusNoContent)
			return
		}
		writeCallbackJSON(response, result)
	})
	mux.HandleFunc("POST /adapt/replicas", func(response http.ResponseWriter, request *http.Request) {
		input, err := readCallbackRequest(request)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		replicas, err := replicaCount(input)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		if callbacks.adaptReplicas == nil {
			writeCallbackError(response, http.StatusInternalServerError, fmt.Errorf("replica adaptation callback is not configured"))
			return
		}
		if err := callbacks.adaptReplicas(request.Context(), replicas); err != nil {
			writeCallbackError(response, http.StatusInternalServerError, err)
			return
		}
		writeCallbackJSON(response, mustJSON(map[string]int32{"replicas": replicas}))
	})
	mux.HandleFunc("POST /adapt/cpu", func(response http.ResponseWriter, request *http.Request) {
		input, err := readCallbackRequest(request)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		multiplier, err := cpuMultiplier(input)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		if callbacks.adaptCPU == nil {
			writeCallbackError(response, http.StatusInternalServerError, fmt.Errorf("CPU adaptation callback is not configured"))
			return
		}
		result, err := callbacks.adaptCPU(request.Context(), multiplier, profile.MaxCPU)
		if err != nil {
			writeCallbackError(response, http.StatusInternalServerError, err)
			return
		}
		writeCallbackJSON(response, mustJSON(result))
	})
	mux.HandleFunc("POST /adapt/tag", func(response http.ResponseWriter, request *http.Request) {
		input, err := readCallbackRequest(request)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		tagUp, updateCPU, err := tagParameters(input)
		if err != nil {
			writeCallbackError(response, http.StatusBadRequest, err)
			return
		}
		if callbacks.adaptTag == nil {
			writeCallbackError(response, http.StatusInternalServerError, fmt.Errorf("tag adaptation callback is not configured"))
			return
		}
		result, err := callbacks.adaptTag(request.Context(), tagUp, updateCPU)
		if err != nil {
			writeCallbackError(response, http.StatusInternalServerError, err)
			return
		}
		writeCallbackJSON(response, mustJSON(result))
	})
	return mux
}

func health(response http.ResponseWriter, _ *http.Request) {
	writeCallbackJSON(response, []byte(`{"status":"UP"}`))
}

func loadRuntimeProfile(path string) (runtimeProfile, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return runtimeProfile{}, fmt.Errorf("failed to read profile %s: %w", path, err)
	}
	var profile runtimeProfile
	if err := yaml.Unmarshal(data, &profile); err != nil {
		return runtimeProfile{}, fmt.Errorf("failed to parse profile %s: %w", path, err)
	}
	if profile.MinReplicas == 0 {
		profile.MinReplicas = 1
	}
	if profile.MaxReplicas == 0 {
		profile.MaxReplicas = 10
	}
	if profile.MaxCPU == 0 {
		profile.MaxCPU = 1000
	}
	if profile.EnabledStrategies == nil {
		profile.EnabledStrategies = []string{strategyReplicas}
	}
	return profile, nil
}

func evaluationResult(input []byte, profile runtimeProfile) ([]byte, error) {
	return evaluationResultWithCPU(input, profile, 0, 0)
}

func evaluationResultWithCPU(input []byte, profile runtimeProfile, currentCPU, initialCPU int64) ([]byte, error) {
	var spec struct {
		Resource struct {
			Spec struct {
				Replicas int32 `json:"replicas"`
			} `json:"spec"`
		} `json:"resource"`
		Metrics []struct {
			Value string `json:"value"`
		} `json:"metrics"`
	}
	if err := json.Unmarshal(input, &spec); err != nil {
		return nil, fmt.Errorf("invalid runtime evaluation payload: %w", err)
	}
	if spec.Resource.Spec.Replicas < 0 {
		return nil, fmt.Errorf("resource.spec.replicas must not be negative")
	}
	if len(spec.Metrics) == 0 {
		return nil, fmt.Errorf("metrics must contain at least one metric")
	}
	var metric map[string]json.RawMessage
	if err := json.Unmarshal([]byte(spec.Metrics[0].Value), &metric); err != nil || metric == nil {
		return nil, fmt.Errorf("metrics[0].value must contain a JSON object")
	}
	currentValue, err := quantityInteger(metric["current_value"], "current_value")
	if err != nil {
		return nil, err
	}
	targetValue, err := quantityInteger(metric["target_value"], "target_value")
	if err != nil {
		return nil, err
	}
	decision, err := evaluate(evaluationInput{
		currentReplicas:    spec.Resource.Spec.Replicas,
		currentMetricValue: currentValue,
		targetMetricValue:  targetValue,
		currentMCPU:        currentCPU,
		initialMCPU:        initialCPU,
		minReplicas:        profile.MinReplicas,
		maxReplicas:        profile.MaxReplicas,
		maxCPU:             profile.MaxCPU,
		enabledStrategies:  profile.EnabledStrategies,
	})
	if err != nil {
		return nil, err
	}
	if decision == nil {
		return nil, nil
	}
	return json.Marshal(decision)
}

func cpuMultiplier(input []byte) (float64, error) {
	var spec struct {
		Evaluation struct {
			Parameters struct {
				Multiplier *float64 `json:"cpu_multiplier"`
			} `json:"parameters"`
		} `json:"evaluation"`
	}
	if err := json.Unmarshal(input, &spec); err != nil {
		return 0, fmt.Errorf("invalid CPU adaptation payload: %w", err)
	}
	if spec.Evaluation.Parameters.Multiplier == nil || math.IsNaN(*spec.Evaluation.Parameters.Multiplier) || math.IsInf(*spec.Evaluation.Parameters.Multiplier, 0) {
		return 0, fmt.Errorf("evaluation.parameters.cpu_multiplier must be a finite number")
	}
	return *spec.Evaluation.Parameters.Multiplier, nil
}

func tagParameters(input []byte) (bool, bool, error) {
	var spec struct {
		Evaluation struct {
			Parameters struct {
				TagUp     *bool `json:"tag_up"`
				UpdateCPU *bool `json:"update_cpu"`
			} `json:"parameters"`
		} `json:"evaluation"`
	}
	if err := json.Unmarshal(input, &spec); err != nil {
		return false, false, fmt.Errorf("invalid tag adaptation payload: %w", err)
	}
	if spec.Evaluation.Parameters.TagUp == nil {
		return false, false, fmt.Errorf("evaluation.parameters.tag_up must be a boolean")
	}
	updateCPU := false
	if spec.Evaluation.Parameters.UpdateCPU != nil {
		updateCPU = *spec.Evaluation.Parameters.UpdateCPU
	}
	return *spec.Evaluation.Parameters.TagUp, updateCPU, nil
}

func strategyEnabled(strategies []string, expected string) bool {
	for _, strategy := range strategies {
		if strategy == expected {
			return true
		}
	}
	return false
}

func quantityInteger(raw json.RawMessage, field string) (int64, error) {
	if len(raw) == 0 || string(raw) == "null" {
		return 0, fmt.Errorf("missing metric field %q", field)
	}
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		var number json.Number
		decoder := json.NewDecoder(bytes.NewReader(raw))
		decoder.UseNumber()
		if decodeErr := decoder.Decode(&number); decodeErr != nil {
			return 0, fmt.Errorf("invalid metric field %q", field)
		}
		value = number.String()
	}
	quantity, err := resource.ParseQuantity(value)
	if err != nil {
		return 0, fmt.Errorf("invalid metric field %q: %w", field, err)
	}
	decimal := quantity.AsDec()
	integer := new(big.Int).Set(decimal.UnscaledBig())
	scale := int(decimal.Scale())
	if scale > 0 {
		integer.Quo(integer, new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(scale)), nil))
	} else if scale < 0 {
		integer.Mul(integer, new(big.Int).Exp(big.NewInt(10), big.NewInt(int64(-scale)), nil))
	}
	if !integer.IsInt64() {
		return 0, fmt.Errorf("metric field %q exceeds the supported range", field)
	}
	return integer.Int64(), nil
}

func replicaCount(input []byte) (int32, error) {
	var spec struct {
		Evaluation struct {
			Parameters struct {
				Replicas *int32 `json:"replicas"`
			} `json:"parameters"`
		} `json:"evaluation"`
	}
	if err := json.Unmarshal(input, &spec); err != nil {
		return 0, fmt.Errorf("invalid replica adaptation payload: %w", err)
	}
	if spec.Evaluation.Parameters.Replicas == nil || *spec.Evaluation.Parameters.Replicas < 0 {
		return 0, fmt.Errorf("evaluation.parameters.replicas must be a non-negative integer")
	}
	return *spec.Evaluation.Parameters.Replicas, nil
}

func readCallbackRequest(request *http.Request) ([]byte, error) {
	input, err := io.ReadAll(io.LimitReader(request.Body, maxCallbackRequestBytes))
	if err != nil {
		return nil, fmt.Errorf("failed to read callback request: %w", err)
	}
	if !json.Valid(input) {
		return nil, fmt.Errorf("callback request must contain valid JSON")
	}
	return input, nil
}

func writeCallbackJSON(response http.ResponseWriter, body []byte) {
	response.Header().Set("Content-Type", "application/json")
	response.WriteHeader(http.StatusOK)
	_, _ = response.Write(body)
	_, _ = response.Write([]byte("\n"))
}

func writeCallbackError(response http.ResponseWriter, status int, err error) {
	response.Header().Set("Content-Type", "application/json")
	response.WriteHeader(status)
	_ = json.NewEncoder(response).Encode(map[string]string{"error": err.Error()})
}

func mustJSON(value any) []byte {
	body, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return body
}

func requiredEnvironmentValue(name string) (string, error) {
	value := os.Getenv(name)
	if strings.TrimSpace(value) == "" {
		return "", fmt.Errorf("%s must not be blank", name)
	}
	return value, nil
}
