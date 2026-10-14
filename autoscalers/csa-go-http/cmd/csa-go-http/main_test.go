package main

import (
	"net/http"
	"net/http/httptest"
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
