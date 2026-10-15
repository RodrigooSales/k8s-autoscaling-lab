package main

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
)

var httpTagLadder = []string{"100k", "200k", "400k", "600k", "800k"}

type tagAdapter struct {
	state adaptationState
}

func (adapter tagAdapter) adapt(ctx context.Context, tagUp, updateCPU bool) (map[string]any, error) {
	deployment, err := adapter.state.deployment(ctx)
	if err != nil {
		return nil, err
	}
	if rolloutInProgress(deployment) {
		return map[string]any{"result": "skip"}, nil
	}
	container := findHTTPDeploymentContainer(deployment, "znn")
	if container == nil {
		return nil, fmt.Errorf("Container 'znn' not found in Deployment")
	}
	image, currentTag, ok := splitHTTPImageTag(container.Image)
	if !ok {
		return nil, fmt.Errorf("could not identify tag in image %s", container.Image)
	}
	initialTagValue, err := adapter.state.storedData(ctx, "tag")
	if err != nil {
		return nil, err
	}
	initialTag, _ := initialTagValue.(string)
	if initialTag == "" {
		initialTag = currentTag
	}
	if err := adapter.state.storeData(ctx, "tag", currentTag); err != nil {
		return nil, err
	}
	if tagUp && tagInHTTPLadder(initialTag) && currentTag == initialTag {
		return map[string]any{"result": "skip"}, nil
	}
	newTag, canChange := adjacentHTTPTag(currentTag, tagUp)
	if !canChange {
		return map[string]any{"result": "skip"}, nil
	}
	container.Image = image + ":" + newTag

	if updateCPU {
		initialCPU, err := adapter.state.initialMCPU(ctx, deployment)
		if err != nil {
			return nil, err
		}
		pods, err := adapter.state.pods(ctx, deployment)
		if err != nil {
			return nil, err
		}
		currentCPU, err := currentPodMCPU(pods)
		if err == nil && currentCPU != initialCPU {
			for index := range deployment.Spec.Template.Spec.Containers {
				limits := deployment.Spec.Template.Spec.Containers[index].Resources.Limits
				if limits == nil {
					limits = corev1.ResourceList{}
				}
				limits[corev1.ResourceCPU] = *resource.NewMilliQuantity(currentCPU, resource.DecimalSI)
				deployment.Spec.Template.Spec.Containers[index].Resources.Limits = limits
			}
		}
	}
	patch, err := json.Marshal(deployment)
	if err != nil {
		return nil, fmt.Errorf("failed to encode Deployment patch: %w", err)
	}
	_, err = adapter.state.client.Resource(deploymentResource).
		Namespace(adapter.state.namespace).
		Patch(ctx, adapter.state.deploymentName, types.StrategicMergePatchType, patch, metav1.PatchOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to patch Deployment %s/%s: %w", adapter.state.namespace, adapter.state.deploymentName, err)
	}
	return map[string]any{"tag": newTag}, nil
}

func findHTTPDeploymentContainer(deployment *appsv1.Deployment, name string) *corev1.Container {
	for index := range deployment.Spec.Template.Spec.Containers {
		if deployment.Spec.Template.Spec.Containers[index].Name == name {
			return &deployment.Spec.Template.Spec.Containers[index]
		}
	}
	return nil
}

func splitHTTPImageTag(image string) (string, string, bool) {
	separator := strings.LastIndex(image, ":")
	if separator <= strings.LastIndex(image, "/") || separator == len(image)-1 {
		return "", "", false
	}
	return image[:separator], image[separator+1:], true
}

func adjacentHTTPTag(tag string, up bool) (string, bool) {
	for index, value := range httpTagLadder {
		if value != tag {
			continue
		}
		next := index - 1
		if up {
			next = index + 1
		}
		if next < 0 || next >= len(httpTagLadder) {
			return "", false
		}
		return httpTagLadder[next], true
	}
	return "", false
}

func tagInHTTPLadder(tag string) bool {
	for _, value := range httpTagLadder {
		if value == tag {
			return true
		}
	}
	return false
}
