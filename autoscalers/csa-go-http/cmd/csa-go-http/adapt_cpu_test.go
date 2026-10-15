package main

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	dynamicfake "k8s.io/client-go/dynamic/fake"
	ktesting "k8s.io/client-go/testing"
)

func TestCPUAdapterResizesPodsWithinInitialAndMaximumBounds(t *testing.T) {
	state, client := adaptationTestState(t, `{"cpu_limit":500,"tag":"100k"}`, "registry.k8s.lab/kube-znn:600k", "600m")

	result, err := (cpuAdapter{state: state}).adapt(context.Background(), 1.3, 750)
	if err != nil {
		t.Fatalf("adapt() error = %v", err)
	}
	if result["cpu"] != int64(750) {
		t.Fatalf("adapt() result = %v, want 750m", result)
	}

	patchAction := findPatchAction(t, client.Actions(), "pods", "resize")
	if patchAction.GetPatchType() != types.StrategicMergePatchType {
		t.Fatalf("Pod patch type = %s", patchAction.GetPatchType())
	}
	var patch map[string]any
	if err := json.Unmarshal(patchAction.GetPatch(), &patch); err != nil {
		t.Fatal(err)
	}
	containers, _, _ := unstructured.NestedSlice(patch, "spec", "containers")
	if len(containers) != 2 {
		t.Fatalf("resize containers = %v, want znn and nginx", containers)
	}
}

func TestCPUResizePatchUsesMillicoreQuantities(t *testing.T) {
	patch, err := cpuResizePatch(500)
	if err != nil {
		t.Fatal(err)
	}
	if string(patch) != `{"spec":{"containers":[{"name":"znn","resources":{"limits":{"cpu":"500m"}}},{"name":"nginx","resources":{"limits":{"cpu":"500m"}}}]}}` {
		t.Fatalf("resize patch = %s", patch)
	}
}

func TestCPUStateReadsCurrentAndInitialLimits(t *testing.T) {
	state, _ := adaptationTestState(t, `{"cpu_limit":500,"tag":"100k"}`, "registry.k8s.lab/kube-znn:600k", "600m")

	current, initial, err := state.cpuState(context.Background())
	if err != nil {
		t.Fatalf("cpuState() error = %v", err)
	}
	if current != 600 || initial != 500 {
		t.Fatalf("cpuState() = (%d, %d), want (600, 500)", current, initial)
	}
}

func TestInitialCPUIsPersistedThroughCSAAnnotationWhenMissing(t *testing.T) {
	state, client := adaptationTestState(t, `{}`, "registry.k8s.lab/kube-znn:600k", "600m")
	deployment, err := state.deployment(context.Background())
	if err != nil {
		t.Fatalf("deployment() error = %v", err)
	}

	initial, err := state.initialMCPU(context.Background(), deployment)
	if err != nil {
		t.Fatalf("initialMCPU() error = %v", err)
	}
	if initial != 600 {
		t.Fatalf("initialMCPU() = %d, want 600", initial)
	}
	patch := findPatchAction(t, client.Actions(), "pods", "")
	if !strings.Contains(string(patch.GetPatch()), `"csa.custom-self-adapter.net/initialData":"{\"cpu_limit\":600}"`) {
		t.Fatalf("initialData Pod patch = %s", patch.GetPatch())
	}
}

func TestInitialDataReadsCustomSelfAdapterStatusSubresource(t *testing.T) {
	state, client := adaptationTestState(t, `{"cpu_limit":600}`, "registry.k8s.lab/kube-znn:600k", "600m")
	if _, err := state.initialData(context.Background()); err != nil {
		t.Fatalf("initialData() error = %v", err)
	}

	for _, action := range client.Actions() {
		if action.GetVerb() == "get" && action.GetResource().Resource == "customselfadapters" {
			if action.GetSubresource() != "status" {
				t.Fatalf("CustomSelfAdapter GET subresource = %q, want status", action.GetSubresource())
			}
			return
		}
	}
	t.Fatal("no CustomSelfAdapter status GET action was recorded")
}

func adaptationTestState(t *testing.T, initialData, image, currentCPU string) (adaptationState, *dynamicfake.FakeDynamicClient) {
	t.Helper()
	scheme := runtime.NewScheme()
	if err := appsv1.AddToScheme(scheme); err != nil {
		t.Fatal(err)
	}
	if err := corev1.AddToScheme(scheme); err != nil {
		t.Fatal(err)
	}
	replicas := int32(1)
	deployment := &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{Name: "kube-znn", Namespace: "default", Generation: 1},
		Spec: appsv1.DeploymentSpec{
			Replicas: &replicas,
			Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "kube-znn"}},
			Template: corev1.PodTemplateSpec{Spec: corev1.PodSpec{Containers: []corev1.Container{
				{Name: "znn", Image: image, Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(currentCPU)}}},
				{Name: "nginx", Image: "nginx:stable", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(currentCPU)}}},
			}}},
		},
		Status: appsv1.DeploymentStatus{ObservedGeneration: 1, UpdatedReplicas: 1, AvailableReplicas: 1},
	}
	deploymentObject, err := runtime.DefaultUnstructuredConverter.ToUnstructured(deployment)
	if err != nil {
		t.Fatal(err)
	}
	deploymentObject["apiVersion"] = "apps/v1"
	deploymentObject["kind"] = "Deployment"
	csa := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": "custom-self-adapter.net/v1",
		"kind":       "CustomSelfAdapter",
		"metadata":   map[string]any{"name": "csa-znn-http", "namespace": "default"},
		"status":     map[string]any{"initialData": initialData},
	}}
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: "kube-znn-pod", Namespace: "default", Labels: map[string]string{"app": "kube-znn"}},
		Spec: corev1.PodSpec{Containers: []corev1.Container{
			{Name: "znn", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(currentCPU)}}},
			{Name: "nginx", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(currentCPU)}}},
		}},
	}
	client := dynamicfake.NewSimpleDynamicClient(scheme, &unstructured.Unstructured{Object: deploymentObject}, csa, pod)
	client.PrependReactor("patch", "pods", func(_ ktesting.Action) (bool, runtime.Object, error) {
		return true, &unstructured.Unstructured{Object: map[string]any{}}, nil
	})
	client.PrependReactor("patch", "deployments", func(_ ktesting.Action) (bool, runtime.Object, error) {
		return true, &unstructured.Unstructured{Object: deploymentObject}, nil
	})
	state := adaptationState{client: client, namespace: "default", csaName: "csa-znn-http", deploymentName: "kube-znn"}
	return state, client
}

func findPatchAction(t *testing.T, actions []ktesting.Action, resource, subresource string) ktesting.PatchAction {
	t.Helper()
	for _, action := range actions {
		if action.GetVerb() != "patch" || action.GetResource().Resource != resource || action.GetSubresource() != subresource {
			continue
		}
		patch, ok := action.(ktesting.PatchAction)
		if ok {
			return patch
		}
	}
	t.Fatalf("no PATCH action for %s/%s in %v", resource, subresource, actions)
	return nil
}
