package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHealthWorksWithoutKubernetesCredentials(t *testing.T) {
	response := httptest.NewRecorder()
	health(response, httptest.NewRequest(http.MethodGet, "/health", nil))

	if response.Code != http.StatusOK {
		t.Errorf("health status = %d, want %d", response.Code, http.StatusOK)
	}
	if response.Body.String() != "{\"status\":\"UP\"}\n" {
		t.Errorf("health body = %q, want UP response", response.Body.String())
	}
}

func TestMetricCallbackConsumesGenericRuntimePayload(t *testing.T) {
	request := httptest.NewRequest(http.MethodPost, "/metric", strings.NewReader(`{"resource":{"spec":{"replicas":2}},"kubernetesMetrics":[{"spec":{"external":{"target":{"value":"1000"}}},"external":{"current":{"value":"950000"}}}]}`))
	response := httptest.NewRecorder()
	testHTTPHandler(nil).ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("metric status = %d, want %d", response.Code, http.StatusOK)
	}
	var result map[string]any
	if err := json.Unmarshal(response.Body.Bytes(), &result); err != nil {
		t.Fatal(err)
	}
	if result["current_replicas"] != float64(2) || result["target_value"] != "1000" || result["current_value"] != "950000" {
		t.Errorf("metric result = %v, want runtime callback values", result)
	}
}

func TestEvaluateCallbackReturnsRuntimeDecisionOrEmptyObject(t *testing.T) {
	profile := runtimeProfile{
		MinReplicas:       1,
		MaxReplicas:       5,
		MaxCPU:            750,
		EnabledStrategies: []string{strategyReplicas},
	}
	handler := testHTTPHandler(&profile)
	tests := []struct {
		name string
		body string
		want string
	}{
		{
			name: "replica decision",
			body: `{"resource":{"spec":{"replicas":2}},"metrics":[{"value":"{\"current_value\":\"950000\",\"target_value\":\"1000\"}"}]}`,
			want: `{"strategy":"adapt_replicas","parameters":{"replicas":2}}`,
		},
		{
			name: "no decision",
			body: `{"resource":{"spec":{"replicas":2}},"metrics":[{"value":"{\"current_value\":\"940000\",\"target_value\":\"1000\"}"}]}`,
			want: `{}`,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/evaluate", strings.NewReader(test.body))
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			if response.Code != http.StatusOK {
				t.Fatalf("evaluate status = %d, want %d: %s", response.Code, http.StatusOK, response.Body.String())
			}
			var actual, expected any
			if err := json.Unmarshal(response.Body.Bytes(), &actual); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal([]byte(test.want), &expected); err != nil {
				t.Fatal(err)
			}
			if !jsonEqual(actual, expected) {
				t.Errorf("evaluate result = %s, want %s", response.Body.String(), test.want)
			}
		})
	}
}

func TestReplicaCallback(t *testing.T) {
	var adapted int32
	profile := runtimeProfile{MinReplicas: 1, MaxReplicas: 5, EnabledStrategies: []string{strategyReplicas}}
	handler := newHTTPHandler(profile, func(_ context.Context, replicas int32) error {
		adapted = replicas
		return nil
	})
	request := httptest.NewRequest(http.MethodPost, "/adapt/replicas", strings.NewReader(`{"evaluation":{"parameters":{"replicas":4}}}`))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK || adapted != 4 || response.Body.String() != "{\"replicas\":4}\n" {
		t.Fatalf("replica callback = status %d, body %q, adapted %d", response.Code, response.Body.String(), adapted)
	}

}

func testHTTPHandler(profile *runtimeProfile) http.Handler {
	configuration := runtimeProfile{MinReplicas: 1, MaxReplicas: 5, MaxCPU: 750, EnabledStrategies: []string{strategyReplicas}}
	if profile != nil {
		configuration = *profile
	}
	return newHTTPHandler(configuration, func(context.Context, int32) error { return nil })
}

func jsonEqual(left, right any) bool {
	leftJSON, _ := json.Marshal(left)
	rightJSON, _ := json.Marshal(right)
	return string(leftJSON) == string(rightJSON)
}
