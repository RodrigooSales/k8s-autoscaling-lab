package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/apis/meta/v1/unstructured"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"
)

var (
	podsResource = schema.GroupVersionResource{Version: "v1", Resource: "pods"}
	csaResource  = schema.GroupVersionResource{
		Group: "custom-self-adapter.net", Version: "v1", Resource: "customselfadapters",
	}
)

const initialDataAnnotation = "csa.custom-self-adapter.net/initialData"

type adaptationState struct {
	client         dynamic.Interface
	namespace      string
	csaName        string
	deploymentName string
}

func (state adaptationState) deployment(ctx context.Context) (*appsv1.Deployment, error) {
	object, err := state.client.Resource(deploymentResource).
		Namespace(state.namespace).
		Get(ctx, state.deploymentName, metav1.GetOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to read Deployment %s/%s: %w", state.namespace, state.deploymentName, err)
	}
	deployment := &appsv1.Deployment{}
	if err := runtime.DefaultUnstructuredConverter.FromUnstructured(object.Object, deployment); err != nil {
		return nil, fmt.Errorf("failed to decode Deployment %s/%s: %w", state.namespace, state.deploymentName, err)
	}
	return deployment, nil
}

func (state adaptationState) pods(ctx context.Context, deployment *appsv1.Deployment) ([]corev1.Pod, error) {
	selector, err := metav1.LabelSelectorAsSelector(deployment.Spec.Selector)
	if err != nil {
		return nil, fmt.Errorf("invalid Deployment selector: %w", err)
	}
	list, err := state.client.Resource(podsResource).
		Namespace(state.namespace).
		List(ctx, metav1.ListOptions{LabelSelector: selector.String()})
	if err != nil {
		return nil, fmt.Errorf("failed to list Pods for Deployment %s/%s: %w", state.namespace, state.deploymentName, err)
	}
	pods := make([]corev1.Pod, 0, len(list.Items))
	for _, item := range list.Items {
		pod := corev1.Pod{}
		if err := runtime.DefaultUnstructuredConverter.FromUnstructured(item.Object, &pod); err != nil {
			return nil, fmt.Errorf("failed to decode Pod %s: %w", item.GetName(), err)
		}
		pods = append(pods, pod)
	}
	return pods, nil
}

func currentPodMCPU(pods []corev1.Pod) (int64, error) {
	var current int64
	found := false
	for _, pod := range pods {
		if len(pod.Spec.Containers) == 0 {
			continue
		}
		quantity, exists := pod.Spec.Containers[0].Resources.Limits[corev1.ResourceCPU]
		if !exists {
			continue
		}
		value := quantity.MilliValue()
		if !found || value > current {
			current = value
			found = true
		}
	}
	if !found || current == 0 {
		return 0, fmt.Errorf("current CPU limit not found or unparsable")
	}
	return current, nil
}

func deploymentSpecMCPU(deployment *appsv1.Deployment) (int64, error) {
	for _, container := range deployment.Spec.Template.Spec.Containers {
		if quantity, exists := container.Resources.Limits[corev1.ResourceCPU]; exists {
			return quantity.MilliValue(), nil
		}
	}
	return 0, nil
}

func rolloutInProgress(deployment *appsv1.Deployment) bool {
	desiredGeneration := deployment.Generation
	replicas := int32(0)
	if deployment.Spec.Replicas != nil {
		replicas = *deployment.Spec.Replicas
	}
	return !(deployment.Status.ObservedGeneration >= desiredGeneration &&
		deployment.Status.UpdatedReplicas == replicas &&
		deployment.Status.AvailableReplicas == replicas)
}

func (state adaptationState) cpuState(ctx context.Context) (int64, int64, error) {
	deployment, err := state.deployment(ctx)
	if err != nil {
		return 0, 0, err
	}
	pods, err := state.pods(ctx, deployment)
	if err != nil {
		return 0, 0, err
	}
	if len(pods) == 0 {
		return 0, 0, fmt.Errorf("no Pods found for Deployment %s/%s", state.namespace, state.deploymentName)
	}
	current, err := currentPodMCPU(pods)
	if err != nil {
		return 0, 0, err
	}
	initial, err := state.initialMCPU(ctx, deployment)
	return current, initial, err
}

func (state adaptationState) initialMCPU(ctx context.Context, deployment *appsv1.Deployment) (int64, error) {
	value, err := state.storedData(ctx, "cpu_limit")
	if err != nil {
		return 0, err
	}
	if value == nil || value == "" || value == json.Number("0") {
		value, err = deploymentSpecMCPU(deployment)
		if err != nil {
			return 0, err
		}
		if value == int64(0) {
			return 0, fmt.Errorf("no CPU limits found in the Deployment spec")
		}
		if err := state.storeData(ctx, "cpu_limit", value); err != nil {
			return 0, err
		}
	}
	return adaptationInteger(value)
}

func (state adaptationState) storedData(ctx context.Context, field string) (any, error) {
	data, err := state.initialData(ctx)
	if err != nil || data == nil {
		return nil, err
	}
	return data[field], nil
}

func (state adaptationState) initialData(ctx context.Context) (map[string]any, error) {
	object, err := state.client.Resource(csaResource).
		Namespace(state.namespace).
		Get(ctx, state.csaName, metav1.GetOptions{}, "status")
	if err != nil {
		return nil, fmt.Errorf("failed to read CustomSelfAdapter status %s/%s: %w", state.namespace, state.csaName, err)
	}
	serialized, found, err := unstructured.NestedString(object.Object, "status", "initialData")
	if err != nil {
		return nil, fmt.Errorf("invalid status.initialData: %w", err)
	}
	if !found || serialized == "" {
		return nil, nil
	}
	decoder := json.NewDecoder(bytes.NewBufferString(serialized))
	decoder.UseNumber()
	var data map[string]any
	if err := decoder.Decode(&data); err != nil || data == nil {
		if err == nil {
			err = fmt.Errorf("must contain a JSON object")
		}
		return nil, fmt.Errorf("invalid status.initialData JSON: %w", err)
	}
	return data, nil
}

func (state adaptationState) storeData(ctx context.Context, field string, value any) error {
	data, err := state.initialData(ctx)
	if err != nil {
		return err
	}
	if existing := data[field]; existing != nil && existing != "" {
		return nil
	}
	if data == nil {
		data = map[string]any{}
	}
	data[field] = value
	encoded, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("failed to encode initialData: %w", err)
	}
	annotations := map[string]string{initialDataAnnotation: string(encoded)}
	patch, err := json.Marshal(map[string]any{"metadata": map[string]any{"annotations": annotations}})
	if err != nil {
		return fmt.Errorf("failed to encode CSA Pod annotation: %w", err)
	}
	_, err = state.client.Resource(podsResource).
		Namespace(state.namespace).
		Patch(ctx, state.csaName, types.MergePatchType, patch, metav1.PatchOptions{})
	if err != nil {
		return fmt.Errorf("failed to store initialData on CSA Pod %s/%s: %w", state.namespace, state.csaName, err)
	}
	return nil
}

func adaptationInteger(value any) (int64, error) {
	switch value := value.(type) {
	case json.Number:
		result, err := value.Int64()
		if err == nil {
			return result, nil
		}
	case int:
		return int64(value), nil
	case int64:
		return value, nil
	case float64:
		return int64(value), nil
	case string:
		var result int64
		if _, err := fmt.Sscan(value, &result); err == nil {
			return result, nil
		}
	}
	return 0, fmt.Errorf("stored CPU limit must be an integer")
}
