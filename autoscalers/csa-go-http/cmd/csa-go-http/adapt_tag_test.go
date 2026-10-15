package main

import (
	"context"
	"encoding/json"
	"testing"

	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/types"
)

func TestTagAdapterPatchesNextLowerImageTag(t *testing.T) {
	state, client := adaptationTestState(t, `{"cpu_limit":500,"tag":"100k"}`, "registry.k8s.lab/kube-znn:600k", "600m")

	result, err := (tagAdapter{state: state}).adapt(context.Background(), false, false)
	if err != nil {
		t.Fatalf("adapt() error = %v", err)
	}
	if result["tag"] != "400k" {
		t.Fatalf("adapt() result = %v, want tag 400k", result)
	}
	patchAction := findPatchAction(t, client.Actions(), "deployments", "")
	if patchAction.GetPatchType() != types.StrategicMergePatchType {
		t.Fatalf("Deployment patch type = %s", patchAction.GetPatchType())
	}
	var patch map[string]any
	if err := json.Unmarshal(patchAction.GetPatch(), &patch); err != nil {
		t.Fatal(err)
	}
	containers, found, err := unstructured.NestedSlice(patch, "spec", "template", "spec", "containers")
	if err != nil || !found {
		t.Fatalf("Deployment patch containers = %v, found %v, error %v", containers, found, err)
	}
	container := containers[0].(map[string]any)
	if container["image"] != "registry.k8s.lab/kube-znn:400k" {
		t.Fatalf("patched image = %v", container["image"])
	}
}

func TestTagAdapterRestoresCurrentCPUWhenRequested(t *testing.T) {
	state, client := adaptationTestState(t, `{"cpu_limit":500,"tag":"100k"}`, "registry.k8s.lab/kube-znn:600k", "600m")

	result, err := (tagAdapter{state: state}).adapt(context.Background(), false, true)
	if err != nil {
		t.Fatalf("adapt() error = %v", err)
	}
	if result["tag"] != "400k" {
		t.Fatalf("adapt() result = %v, want tag 400k", result)
	}
	patchAction := findPatchAction(t, client.Actions(), "deployments", "")
	var patch map[string]any
	if err := json.Unmarshal(patchAction.GetPatch(), &patch); err != nil {
		t.Fatal(err)
	}
	containers, _, _ := unstructured.NestedSlice(patch, "spec", "template", "spec", "containers")
	for _, value := range containers {
		container := value.(map[string]any)
		resources := container["resources"].(map[string]any)
		limits := resources["limits"].(map[string]any)
		if limits["cpu"] != "600m" {
			t.Fatalf("restored CPU limit = %v, want 600m", limits["cpu"])
		}
	}
}

func TestTagAdapterDoesNotMoveAboveTheInitialTag(t *testing.T) {
	state, client := adaptationTestState(t, `{"cpu_limit":500,"tag":"600k"}`, "registry.k8s.lab/kube-znn:600k", "600m")

	result, err := (tagAdapter{state: state}).adapt(context.Background(), true, false)
	if err != nil {
		t.Fatalf("adapt() error = %v", err)
	}
	if result["result"] != "skip" {
		t.Fatalf("adapt() result = %v, want skip", result)
	}
	for _, action := range client.Actions() {
		if action.GetVerb() == "patch" && action.GetResource().Resource == "deployments" {
			t.Fatal("Deployment was patched above the initial image tag")
		}
	}
}
