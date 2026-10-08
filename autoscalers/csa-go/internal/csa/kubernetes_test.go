package csa

import (
	"context"
	"encoding/json"
	"reflect"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	dynamicfake "k8s.io/client-go/dynamic/fake"
	k8sfake "k8s.io/client-go/kubernetes/fake"
	k8stesting "k8s.io/client-go/testing"
)

func TestKubernetesClientUsesExpectedResourcesAndStrategicPatch(t *testing.T) {
	var calls []string
	coreClient := k8sfake.NewSimpleClientset(&corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: "csa", Namespace: "csa-ns", Annotations: map[string]string{"existing": "value"}},
	})
	coreClient.PrependReactor("get", "pods", func(action k8stesting.Action) (bool, runtime.Object, error) {
		calls = append(calls, "GET pods "+action.GetNamespace()+"/"+action.(k8stesting.GetAction).GetName())
		return false, nil, nil
	})
	coreClient.PrependReactor("patch", "pods", func(action k8stesting.Action) (bool, runtime.Object, error) {
		patchAction := action.(k8stesting.PatchAction)
		calls = append(calls, "PATCH pods "+action.GetNamespace()+"/"+patchAction.GetName())
		if patchAction.GetPatchType() != types.StrategicMergePatchType {
			t.Errorf("patch type = %q, want strategic merge patch", patchAction.GetPatchType())
		}
		var patched corev1.Pod
		if err := json.Unmarshal(patchAction.GetPatch(), &patched); err != nil {
			t.Errorf("decode patch body: %v", err)
		}
		if patched.Annotations["existing"] != "value" {
			t.Errorf("patch body did not preserve annotations: %#v", patched.Annotations)
		}
		return false, nil, nil
	})

	deployment := unstructuredFor(t, &appsv1.Deployment{
		TypeMeta:   metav1.TypeMeta{APIVersion: "apps/v1", Kind: "Deployment"},
		ObjectMeta: metav1.ObjectMeta{Name: "target", Namespace: "work"},
		Spec:       appsv1.DeploymentSpec{Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "demo"}}},
	})
	pod := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": "v1", "kind": "Pod", "metadata": map[string]any{"name": "worker", "namespace": "work", "labels": map[string]any{"app": "demo"}},
		"spec": map[string]any{"containers": []any{map[string]any{"name": "znn", "resources": map[string]any{"limits": map[string]any{"cpu": "500m"}}}}},
	}}
	dynamicClient := dynamicfake.NewSimpleDynamicClientWithCustomListKinds(runtime.NewScheme(), map[schema.GroupVersionResource]string{
		deploymentResource: "DeploymentList", podResource: "PodList", csaResource: "CustomSelfAdapterList",
	}, deployment, pod)
	dynamicClient.PrependReactor("get", "deployments", func(action k8stesting.Action) (bool, runtime.Object, error) {
		calls = append(calls, "GET deployments "+action.GetNamespace()+"/"+action.(k8stesting.GetAction).GetName())
		return false, nil, nil
	})
	dynamicClient.PrependReactor("list", "pods", func(action k8stesting.Action) (bool, runtime.Object, error) {
		selector := action.(k8stesting.ListAction).GetListRestrictions().Labels.String()
		if selector != "app=demo" {
			t.Errorf("label selector = %q, want app=demo", selector)
		}
		calls = append(calls, "LIST pods "+action.GetNamespace())
		return true, &unstructured.UnstructuredList{Items: []unstructured.Unstructured{*pod.DeepCopy()}}, nil
	})
	dynamicClient.PrependReactor("get", "customselfadapters", func(action k8stesting.Action) (bool, runtime.Object, error) {
		if action.GetSubresource() != "status" {
			t.Errorf("CSA GET subresource = %q, want status", action.GetSubresource())
		}
		calls = append(calls, "GET customselfadapters/status "+action.GetNamespace()+"/"+action.(k8stesting.GetAction).GetName())
		return true, &unstructured.Unstructured{Object: map[string]any{"status": map[string]any{"initialData": `{"cpu_limit":150}`}}}, nil
	})
	client := &kubernetesClient{core: coreClient, dynamic: dynamicClient}

	gotDeployment, err := client.readDeployment(context.Background(), "target", "work")
	if err != nil || gotDeployment.object.Name != "target" {
		t.Fatalf("readDeployment() = %#v, %v", gotDeployment, err)
	}
	gotPods, err := client.listPods(context.Background(), "work", "app=demo")
	if err != nil || len(gotPods.items) != 1 || !gotPods.items[0].firstContainerResourcesPresent {
		t.Fatalf("listPods() = %#v, %v", gotPods, err)
	}
	if milli, found, err := currentPodMCPU(gotPods); err != nil || !found || milli != 500 {
		t.Fatalf("currentPodMCPU() = %d, %v, %v; want 500, true, nil", milli, found, err)
	}
	status, err := client.getCSAStatus(context.Background(), "csa", "csa-ns")
	if err != nil || status["initialData"] != `{"cpu_limit":150}` {
		t.Fatalf("getCSAStatus() = %#v, %v", status, err)
	}
	ownPod, err := client.readPod(context.Background(), "csa", "csa-ns")
	if err != nil {
		t.Fatal(err)
	}
	if err := client.patchPod(context.Background(), "csa", "csa-ns", ownPod); err != nil {
		t.Fatal(err)
	}

	wantCalls := []string{
		"GET deployments work/target", "LIST pods work", "GET customselfadapters/status csa-ns/csa",
		"GET pods csa-ns/csa", "PATCH pods csa-ns/csa",
	}
	if !reflect.DeepEqual(calls, wantCalls) {
		t.Fatalf("Kubernetes calls = %#v, want %#v", calls, wantCalls)
	}
}

func TestKubernetesClientPreservesResourceFieldPresence(t *testing.T) {
	deployment := &unstructured.Unstructured{Object: map[string]any{
		"apiVersion": "apps/v1", "kind": "Deployment", "metadata": map[string]any{"name": "target", "namespace": "work"},
		"spec": map[string]any{"template": map[string]any{"spec": map[string]any{"containers": []any{
			map[string]any{"name": "without-resources"},
			map[string]any{"name": "empty-resources", "resources": map[string]any{}},
			map[string]any{"name": "with-cpu", "resources": map[string]any{"limits": map[string]any{"cpu": "500m"}}},
		}}}},
	}}
	dynamicClient := dynamicfake.NewSimpleDynamicClientWithCustomListKinds(runtime.NewScheme(), map[schema.GroupVersionResource]string{
		deploymentResource: "DeploymentList",
	}, deployment)
	client := &kubernetesClient{dynamic: dynamicClient}
	got, err := client.readDeployment(context.Background(), "target", "work")
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got.containerResourcesPresent, []bool{false, true, true}) {
		t.Fatalf("resource presence = %#v", got.containerResourcesPresent)
	}
	cpuLimit := got.object.Spec.Template.Spec.Containers[2].Resources.Limits[corev1.ResourceCPU]
	if cpuLimit.String() != "500m" {
		t.Fatalf("decoded CPU limit = %#v", got.object.Spec.Template.Spec.Containers[2].Resources.Limits)
	}
}

func TestKubernetesClientPatchesDeploymentWithStrategicMergeBody(t *testing.T) {
	clientset := k8sfake.NewSimpleClientset()
	wantBody := []byte(`{"apiVersion":"apps/v1","kind":"Deployment","metadata":{"name":"target","namespace":"work"},"spec":{"replicas":3}}`)
	clientset.PrependReactor("patch", "deployments", func(action k8stesting.Action) (bool, runtime.Object, error) {
		patch := action.(k8stesting.PatchAction)
		if action.GetNamespace() != "work" || patch.GetName() != "target" {
			t.Errorf("patch target = %s/%s", action.GetNamespace(), patch.GetName())
		}
		if patch.GetPatchType() != types.StrategicMergePatchType {
			t.Errorf("patch type = %q, want strategic merge", patch.GetPatchType())
		}
		if !reflect.DeepEqual(patch.GetPatch(), wantBody) {
			t.Errorf("patch body = %s, want %s", patch.GetPatch(), wantBody)
		}
		return true, &appsv1.Deployment{}, nil
	})

	client := &kubernetesClient{core: clientset}
	if err := client.patchDeployment(context.Background(), "target", "work", wantBody); err != nil {
		t.Fatal(err)
	}
	if len(clientset.Actions()) != 1 {
		t.Fatalf("actions = %#v, want one patch", clientset.Actions())
	}
}

func TestKubernetesClientUsesPodResizeSubresourceAndStrategicMergePatch(t *testing.T) {
	clientset := k8sfake.NewSimpleClientset()
	wantBody := []byte(`{"spec":{"containers":[{"name":"znn","resources":{"limits":{"cpu":"600m"}}}]}}`)
	clientset.PrependReactor("patch", "pods", func(action k8stesting.Action) (bool, runtime.Object, error) {
		patch := action.(k8stesting.PatchAction)
		if patch.GetSubresource() != "resize" {
			t.Errorf("subresource = %q, want resize", patch.GetSubresource())
		}
		if patch.GetPatchType() != types.StrategicMergePatchType {
			t.Errorf("patch type = %q, want strategic merge", patch.GetPatchType())
		}
		if !reflect.DeepEqual(patch.GetPatch(), wantBody) {
			t.Errorf("patch body = %s, want %s", patch.GetPatch(), wantBody)
		}
		return true, &corev1.Pod{}, nil
	})

	client := &kubernetesClient{core: clientset}
	if err := client.resizePod(context.Background(), "worker", "work", wantBody); err != nil {
		t.Fatal(err)
	}
	if len(clientset.Actions()) != 1 {
		t.Fatalf("actions = %#v, want one patch", clientset.Actions())
	}
}

func unstructuredFor(t *testing.T, object runtime.Object) *unstructured.Unstructured {
	t.Helper()
	content, err := runtime.DefaultUnstructuredConverter.ToUnstructured(object)
	if err != nil {
		t.Fatal(err)
	}
	return &unstructured.Unstructured{Object: content}
}
