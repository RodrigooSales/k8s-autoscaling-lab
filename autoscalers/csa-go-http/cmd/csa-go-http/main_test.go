package main

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
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

func TestEvaluateCallbackReturnsDecisionOrNoContent(t *testing.T) {
	profile := runtimeProfile{
		MinReplicas:       1,
		MaxReplicas:       5,
		MaxCPU:            750,
		EnabledStrategies: []string{strategyReplicas},
	}
	handler := testHTTPHandler(&profile)
	tests := []struct {
		name       string
		body       string
		wantStatus int
		wantBody   string
	}{
		{
			name:       "replica decision",
			body:       `{"resource":{"spec":{"replicas":2}},"metrics":[{"value":"{\"current_value\":\"950000\",\"target_value\":\"1000\"}"}]}`,
			wantStatus: http.StatusOK,
			wantBody:   `{"strategy":"adapt_replicas","parameters":{"replicas":2}}`,
		},
		{
			name:       "no decision",
			body:       `{"resource":{"spec":{"replicas":2}},"metrics":[{"value":"{\"current_value\":\"940000\",\"target_value\":\"1000\"}"}]}`,
			wantStatus: http.StatusNoContent,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(http.MethodPost, "/evaluate", strings.NewReader(test.body))
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			if response.Code != test.wantStatus {
				t.Fatalf("evaluate status = %d, want %d: %s", response.Code, test.wantStatus, response.Body.String())
			}
			if test.wantStatus == http.StatusNoContent {
				if response.Body.Len() != 0 {
					t.Errorf("no-decision body = %q, want empty body", response.Body.String())
				}
				return
			}
			var actual, expected any
			if err := json.Unmarshal(response.Body.Bytes(), &actual); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal([]byte(test.wantBody), &expected); err != nil {
				t.Fatal(err)
			}
			if !jsonEqual(actual, expected) {
				t.Errorf("evaluate result = %s, want %s", response.Body.String(), test.wantBody)
			}
		})
	}
}

func TestLoadRuntimeProfileReadsEnabledStrategies(t *testing.T) {
	path := filepath.Join(t.TempDir(), "profile.yaml")
	profileYAML := []byte("minReplicas: 1\nmaxReplicas: 5\nmaxCPU: 750\nenabled_strategies:\n  - adapt_cpu\n  - adapt_tag\n")
	if err := os.WriteFile(path, profileYAML, 0600); err != nil {
		t.Fatal(err)
	}

	profile, err := loadRuntimeProfile(path)
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(profile.EnabledStrategies, ","); got != "adapt_cpu,adapt_tag" {
		t.Fatalf("enabled strategies = %q, want %q", got, "adapt_cpu,adapt_tag")
	}
}

func TestReplicaCallback(t *testing.T) {
	var adapted int32
	profile := runtimeProfile{MinReplicas: 1, MaxReplicas: 5, EnabledStrategies: []string{strategyReplicas}}
	handler := newHTTPHandler(profile, adaptationCallbacks{adaptReplicas: func(_ context.Context, replicas int32) error {
		adapted = replicas
		return nil
	}})
	request := httptest.NewRequest(http.MethodPost, "/adapt/replicas", strings.NewReader(`{"evaluation":{"parameters":{"replicas":4}}}`))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK || adapted != 4 || response.Body.String() != "{\"replicas\":4}\n" {
		t.Fatalf("replica callback = status %d, body %q, adapted %d", response.Code, response.Body.String(), adapted)
	}

}

func TestCPUCallbackUsesEvaluationMultiplierAndProfileLimit(t *testing.T) {
	var multiplier float64
	var maximum int64
	callbacks := adaptationCallbacks{adaptCPU: func(_ context.Context, actualMultiplier float64, actualMaximum int64) (map[string]any, error) {
		multiplier, maximum = actualMultiplier, actualMaximum
		return map[string]any{"cpu": 650}, nil
	}}
	handler := newHTTPHandler(runtimeProfile{MaxCPU: 750}, callbacks)
	request := httptest.NewRequest(http.MethodPost, "/adapt/cpu", strings.NewReader(`{"evaluation":{"parameters":{"cpu_multiplier":1.3}}}`))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Body.String() != "{\"cpu\":650}\n" {
		t.Fatalf("CPU callback = status %d, body %q", response.Code, response.Body.String())
	}
	if multiplier != 1.3 || maximum != 750 {
		t.Fatalf("CPU adapter received multiplier %v, max %d", multiplier, maximum)
	}
}

func TestTagCallbackUsesEvaluationParameters(t *testing.T) {
	var tagUp, updateCPU bool
	callbacks := adaptationCallbacks{adaptTag: func(_ context.Context, actualTagUp, actualUpdateCPU bool) (map[string]any, error) {
		tagUp, updateCPU = actualTagUp, actualUpdateCPU
		return map[string]any{"tag": "400k"}, nil
	}}
	handler := newHTTPHandler(runtimeProfile{}, callbacks)
	request := httptest.NewRequest(http.MethodPost, "/adapt/tag", strings.NewReader(`{"evaluation":{"parameters":{"tag_up":false,"update_cpu":true}}}`))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK || response.Body.String() != "{\"tag\":\"400k\"}\n" {
		t.Fatalf("tag callback = status %d, body %q", response.Code, response.Body.String())
	}
	if tagUp || !updateCPU {
		t.Fatalf("tag adapter received tag_up=%v update_cpu=%v", tagUp, updateCPU)
	}
}

func TestEvaluateUsesCurrentAndInitialCPUForScaleDown(t *testing.T) {
	profile := runtimeProfile{MinReplicas: 1, MaxReplicas: 5, MaxCPU: 750, EnabledStrategies: []string{strategyCPU}}
	callbacks := adaptationCallbacks{cpuState: func(context.Context) (int64, int64, error) { return 600, 500, nil }}
	handler := newHTTPHandler(profile, callbacks)
	request := httptest.NewRequest(http.MethodPost, "/evaluate", strings.NewReader(`{"resource":{"spec":{"replicas":1}},"metrics":[{"value":"{\"current_value\":\"850000\",\"target_value\":\"1000\"}"}]}`))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK || !strings.Contains(response.Body.String(), `"strategy":"adapt_cpu"`) {
		t.Fatalf("CPU scale-down evaluation = status %d, body %q", response.Code, response.Body.String())
	}
}

func TestCPUCallbackRouteIsRegistered(t *testing.T) {
	request := httptest.NewRequest(http.MethodPost, "/adapt/cpu", strings.NewReader(`{"evaluation":{"parameters":{"cpu_multiplier":1.2}}}`))
	response := httptest.NewRecorder()
	testHTTPHandler(nil).ServeHTTP(response, request)

	if response.Code == http.StatusNotFound {
		t.Fatalf("CPU callback returned %d, route is missing", response.Code)
	}
}

func testHTTPHandler(profile *runtimeProfile) http.Handler {
	configuration := runtimeProfile{MinReplicas: 1, MaxReplicas: 5, MaxCPU: 750, EnabledStrategies: []string{strategyReplicas}}
	if profile != nil {
		configuration = *profile
	}
	return newHTTPHandler(configuration, adaptationCallbacks{adaptReplicas: func(context.Context, int32) error { return nil }})
}

func jsonEqual(left, right any) bool {
	leftJSON, _ := json.Marshal(left)
	rightJSON, _ := json.Marshal(right)
	return string(leftJSON) == string(rightJSON)
}
