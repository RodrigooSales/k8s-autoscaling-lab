package main

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"k8s.io/client-go/rest"
)

const (
	metricAPIPath     = "/apis/external.metrics.k8s.io/v1beta1/namespaces/default/znn_latency_ms_p95"
	deploymentAPIPath = "/apis/apps/v1/namespaces/default/deployments/kube-znn"
)

func TestMetricCollectorReadsMetricBeforeDeploymentAndBuildsSnapshot(t *testing.T) {
	requests := make([]*http.Request, 0, 2)
	server := newMetricAPI(t, &requests, `{"apiVersion":"external.metrics.k8s.io/v1beta1","kind":"ExternalMetricValueList","items":[{"value":"1200.0001"},{"value":"300"}]}`)
	collector := newTestMetricCollector(server.URL, "region=north")
	if collector.loadRESTConfigCalls != 0 {
		t.Fatal("Kubernetes client config was loaded before collection")
	}

	snapshot, err := collector.collect(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if snapshot.currentReplicas != 3 {
		t.Errorf("currentReplicas = %d, want 3", snapshot.currentReplicas)
	}
	if snapshot.currentMetricValue != 1500001 {
		t.Errorf("currentMetricValue = %d, want 1500001", snapshot.currentMetricValue)
	}
	if snapshot.targetMetricValue != 1000 {
		t.Errorf("targetMetricValue = %d, want 1000", snapshot.targetMetricValue)
	}
	if len(requests) != 2 {
		t.Fatalf("received %d requests, want 2", len(requests))
	}
	if requests[0].URL.Path != metricAPIPath {
		t.Errorf("first request path = %q, want external metric path", requests[0].URL.Path)
	}
	if requests[0].URL.Query().Get("labelSelector") != "region=north" {
		t.Errorf("labelSelector = %q, want region=north", requests[0].URL.Query().Get("labelSelector"))
	}
	if requests[1].URL.Path != deploymentAPIPath {
		t.Errorf("second request path = %q, want Deployment path", requests[1].URL.Path)
	}
	if collector.loadRESTConfigCalls != 1 {
		t.Errorf("Kubernetes client config loaded %d times, want once", collector.loadRESTConfigCalls)
	}
}

func TestMetricCollectorOmitsUnsetLabelSelector(t *testing.T) {
	requests := make([]*http.Request, 0, 2)
	server := newMetricAPI(t, &requests, `{"apiVersion":"external.metrics.k8s.io/v1beta1","kind":"ExternalMetricValueList","items":[{"value":"1000"}]}`)
	collector := newTestMetricCollector(server.URL, "")

	if _, err := collector.collect(t.Context()); err != nil {
		t.Fatal(err)
	}
	if len(requests) == 0 {
		t.Fatal("no metric request received")
	}
	if _, exists := requests[0].URL.Query()["labelSelector"]; exists {
		t.Errorf("labelSelector was sent without configuration: %q", requests[0].URL.RawQuery)
	}
}

func TestMetricCollectorStopsWhenExternalMetricHasNoValues(t *testing.T) {
	requests := make([]*http.Request, 0, 2)
	server := newMetricAPI(t, &requests, `{"apiVersion":"external.metrics.k8s.io/v1beta1","kind":"ExternalMetricValueList","items":[]}`)
	collector := newTestMetricCollector(server.URL, "")

	_, err := collector.collect(t.Context())
	if err == nil || !strings.Contains(err.Error(), "no values") {
		t.Fatalf("collect error = %v, want specific missing-values error", err)
	}
	if len(requests) != 1 {
		t.Fatalf("received %d requests, want only the external metric request", len(requests))
	}
	if requests[0].URL.Path != metricAPIPath {
		t.Errorf("request path = %q, want external metric path", requests[0].URL.Path)
	}
}

func TestMetricCollectionConfigDefaultsTargetAndRequiresIntegerValue(t *testing.T) {
	t.Setenv("CSA_NAMESPACE", "default")
	t.Setenv("CSA_DEPLOYMENT_NAME", "kube-znn")
	t.Setenv("CSA_METRIC_NAME", "znn_latency_ms_p95")
	t.Setenv("CSA_METRIC_SELECTOR", "")
	t.Setenv("CSA_TARGET_VALUE", "")

	configuration, err := metricCollectionConfigFromEnv()
	if err != nil {
		t.Fatal(err)
	}
	if configuration.targetMetricValue != 1000 {
		t.Errorf("default target = %d, want 1000", configuration.targetMetricValue)
	}

	t.Setenv("CSA_TARGET_VALUE", "1000.5")
	if _, err := metricCollectionConfigFromEnv(); err == nil {
		t.Fatal("fractional target was accepted, want exact-integer validation error")
	}
}

type testMetricCollector struct {
	*metricCollector
	loadRESTConfigCalls int
}

func newTestMetricCollector(serverURL, selector string) *testMetricCollector {
	collector := &testMetricCollector{metricCollector: newMetricCollector(metricCollectionConfig{
		namespace:         "default",
		deploymentName:    "kube-znn",
		metricName:        "znn_latency_ms_p95",
		metricSelector:    selector,
		targetMetricValue: 1000,
	})}
	collector.loadRESTConfig = func() (*rest.Config, error) {
		collector.loadRESTConfigCalls++
		return &rest.Config{Host: serverURL}, nil
	}
	return collector
}

func newMetricAPI(t *testing.T, requests *[]*http.Request, metricResponse string) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		*requests = append(*requests, request)
		response.Header().Set("Content-Type", "application/json")
		switch request.URL.Path {
		case metricAPIPath:
			_, _ = fmt.Fprint(response, metricResponse)
		case deploymentAPIPath:
			_, _ = fmt.Fprint(response, `{"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":3}}`)
		default:
			http.NotFound(response, request)
		}
	}))
	t.Cleanup(server.Close)
	return server
}
