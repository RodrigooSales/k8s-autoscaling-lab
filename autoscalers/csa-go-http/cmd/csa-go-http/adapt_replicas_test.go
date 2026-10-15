package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/rest"
)

const replicaDeploymentAPIPath = "/apis/apps/v1/namespaces/default/deployments/kube-znn"

func TestReplicaAdapterPatchesOnlyDesiredReplicas(t *testing.T) {
	var requestMethod, requestPath, contentType, requestBody string
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		requestMethod = request.Method
		requestPath = request.URL.Path
		contentType = request.Header.Get("Content-Type")
		body, err := io.ReadAll(request.Body)
		if err != nil {
			t.Errorf("read request body: %v", err)
		}
		requestBody = string(body)
		response.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprint(response, `{"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":4}}`)
	}))
	t.Cleanup(server.Close)
	adapter := replicaAdapter{
		client:         replicaAdapterTestClient(t, server.URL),
		namespace:      "default",
		deploymentName: "kube-znn",
	}

	if err := adapter.adapt(context.Background(), 4); err != nil {
		t.Fatalf("adapt() error = %v", err)
	}
	if requestMethod != http.MethodPatch || requestPath != replicaDeploymentAPIPath {
		t.Fatalf("request = %s %s, want PATCH %s", requestMethod, requestPath, replicaDeploymentAPIPath)
	}
	if contentType != string(types.StrategicMergePatchType) {
		t.Errorf("Content-Type = %q, want %q", contentType, types.StrategicMergePatchType)
	}
	if requestBody != `{"spec":{"replicas":4}}` {
		t.Errorf("patch body = %s, want only spec.replicas", requestBody)
	}
}

func TestReplicaAdapterReturnsDeploymentContextOnPatchFailure(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		response.WriteHeader(http.StatusForbidden)
		_, _ = fmt.Fprint(response, `{"kind":"Status","apiVersion":"v1","status":"Failure","message":"forbidden","reason":"Forbidden","code":403}`)
	}))
	t.Cleanup(server.Close)
	adapter := replicaAdapter{
		client:         replicaAdapterTestClient(t, server.URL),
		namespace:      "work",
		deploymentName: "target",
	}

	err := adapter.adapt(context.Background(), 3)
	if err == nil || !strings.Contains(err.Error(), "Deployment work/target") {
		t.Fatalf("adapt() error = %v, want target Deployment context", err)
	}
}

func replicaAdapterTestClient(t *testing.T, serverURL string) dynamic.Interface {
	t.Helper()
	client, err := dynamic.NewForConfig(&rest.Config{Host: serverURL})
	if err != nil {
		t.Fatalf("create Kubernetes client: %v", err)
	}
	return client
}
