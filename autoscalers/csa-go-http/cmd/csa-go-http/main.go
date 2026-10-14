package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
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
	MinReplicas       int32    `yaml:"minReplicas"`
	MaxReplicas       int32    `yaml:"maxReplicas"`
	MaxCPU            int64    `yaml:"maxCPU"`
	EnabledStrategies []string `yaml:"enabled_strategies"`
}

type replicaAdaptingFunc func(context.Context, int32) error

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
	var adapter replicaAdapter
	var adapterError error
	adaptReplicas := func(ctx context.Context, replicas int32) error {
		clientOnce.Do(func() {
			namespace, err := requiredEnvironmentValue("CSA_NAMESPACE")
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
			adapter = replicaAdapter{
				client:         kubernetesClient,
				namespace:      namespace,
				deploymentName: deploymentName,
			}
		})
		if adapterError != nil {
			return adapterError
		}
		return adapter.adapt(ctx, replicas)
	}

	server := &http.Server{
		Addr:              ":8080",
		Handler:           newHTTPHandler(profile, adaptReplicas),
		ReadHeaderTimeout: 5 * time.Second,
	}
	log.Fatal(server.ListenAndServe())
}

func newHTTPHandler(profile runtimeProfile, adaptReplicas replicaAdaptingFunc) http.Handler {
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
		result, err := evaluationResult(input, profile)
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
		if err := adaptReplicas(request.Context(), replicas); err != nil {
			writeCallbackError(response, http.StatusInternalServerError, err)
			return
		}
		writeCallbackJSON(response, mustJSON(map[string]int32{"replicas": replicas}))
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
