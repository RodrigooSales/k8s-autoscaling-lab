package csa

import (
	"context"
	"encoding/json"
	"fmt"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/schema"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/rest"
)

var csaResource = schema.GroupVersionResource{
	Group: "custom-self-adapter.net", Version: "v1", Resource: "customselfadapters",
}

var deploymentResource = appsv1.SchemeGroupVersion.WithResource("deployments")
var podResource = corev1.SchemeGroupVersion.WithResource("pods")

type kubernetesAPI interface {
	readDeployment(context.Context, string, string) (*deploymentSnapshot, error)
	patchDeployment(context.Context, string, string, []byte) error
	listPods(context.Context, string, string) (*podListSnapshot, error)
	readPod(context.Context, string, string) (*corev1.Pod, error)
	getCSAStatus(context.Context, string, string) (map[string]any, error)
	patchPod(context.Context, string, string, *corev1.Pod) error
	resizePod(context.Context, string, string, []byte) error
}

type deploymentSnapshot struct {
	object                    *appsv1.Deployment
	containerResourcesPresent []bool
}

type podListSnapshot struct {
	items []podSnapshot
}

type podSnapshot struct {
	object                         corev1.Pod
	firstContainerResourcesPresent bool
}

type kubernetesClient struct {
	core    kubernetes.Interface
	dynamic dynamic.Interface
}

func newInClusterKubernetesAPI() (kubernetesAPI, error) {
	config, err := rest.InClusterConfig()
	if err != nil {
		return nil, fmt.Errorf("failed to load in-cluster config: %w", err)
	}
	client, err := newKubernetesClient(config)
	if err != nil {
		return nil, err
	}
	return client, nil
}

func newKubernetesClient(config *rest.Config) (*kubernetesClient, error) {
	httpClient, err := rest.HTTPClientFor(config)
	if err != nil {
		return nil, fmt.Errorf("failed to create Kubernetes HTTP client: %w", err)
	}
	clientset, err := kubernetes.NewForConfigAndClient(config, httpClient)
	if err != nil {
		return nil, fmt.Errorf("failed to create Kubernetes client: %w", err)
	}
	dynamicClient, err := dynamic.NewForConfigAndClient(config, httpClient)
	if err != nil {
		return nil, fmt.Errorf("failed to create Kubernetes dynamic client: %w", err)
	}
	return &kubernetesClient{core: clientset, dynamic: dynamicClient}, nil
}

func (client *kubernetesClient) readDeployment(ctx context.Context, name, namespace string) (*deploymentSnapshot, error) {
	resource := client.dynamic.Resource(deploymentResource).Namespace(namespace)
	object, err := resource.Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to read Deployment %s/%s: %w", namespace, name, err)
	}
	deployment := &appsv1.Deployment{}
	if err := runtime.DefaultUnstructuredConverter.FromUnstructured(object.Object, deployment); err != nil {
		return nil, fmt.Errorf("failed to decode Deployment %s/%s: %w", namespace, name, err)
	}
	return &deploymentSnapshot{object: deployment, containerResourcesPresent: deploymentResourcePresence(object.Object)}, nil
}

func (client *kubernetesClient) listPods(ctx context.Context, namespace, labelSelector string) (*podListSnapshot, error) {
	pods, err := client.dynamic.Resource(podResource).Namespace(namespace).List(ctx, metav1.ListOptions{LabelSelector: labelSelector})
	if err != nil {
		return nil, fmt.Errorf("failed to list Pods in namespace %s: %w", namespace, err)
	}
	result := &podListSnapshot{items: make([]podSnapshot, 0, len(pods.Items))}
	for _, item := range pods.Items {
		pod := corev1.Pod{}
		if err := runtime.DefaultUnstructuredConverter.FromUnstructured(item.Object, &pod); err != nil {
			return nil, fmt.Errorf("failed to decode Pod %s/%s: %w", namespace, item.GetName(), err)
		}
		result.items = append(result.items, podSnapshot{object: pod, firstContainerResourcesPresent: firstContainerResourcesPresent(item.Object)})
	}
	return result, nil
}

func (client *kubernetesClient) readPod(ctx context.Context, name, namespace string) (*corev1.Pod, error) {
	pod, err := client.core.CoreV1().Pods(namespace).Get(ctx, name, metav1.GetOptions{})
	if err != nil {
		return nil, fmt.Errorf("failed to read Pod %s/%s: %w", namespace, name, err)
	}
	return pod, nil
}

func (client *kubernetesClient) getCSAStatus(ctx context.Context, name, namespace string) (map[string]any, error) {
	resource := client.dynamic.Resource(csaResource).Namespace(namespace)
	object, err := resource.Get(ctx, name, metav1.GetOptions{}, "status")
	if err != nil {
		return nil, fmt.Errorf("failed to read CSA status %s/%s: %w", namespace, name, err)
	}
	status, _ := object.Object["status"].(map[string]any)
	return status, nil
}

func (client *kubernetesClient) patchPod(ctx context.Context, name, namespace string, pod *corev1.Pod) error {
	body, err := json.Marshal(pod)
	if err != nil {
		return fmt.Errorf("failed to encode Pod %s/%s patch: %w", namespace, name, err)
	}
	_, err = client.core.CoreV1().Pods(namespace).Patch(ctx, name, types.StrategicMergePatchType, body, metav1.PatchOptions{})
	if err != nil {
		return fmt.Errorf("failed to patch Pod %s/%s: %w", namespace, name, err)
	}
	return nil
}

func (client *kubernetesClient) patchDeployment(ctx context.Context, name, namespace string, body []byte) error {
	_, err := client.core.AppsV1().Deployments(namespace).Patch(ctx, name, types.StrategicMergePatchType, body, metav1.PatchOptions{})
	if err != nil {
		return fmt.Errorf("failed to patch Deployment %s/%s: %w", namespace, name, err)
	}
	return nil
}

func (client *kubernetesClient) resizePod(ctx context.Context, name, namespace string, body []byte) error {
	_, err := client.core.CoreV1().Pods(namespace).Patch(ctx, name, types.StrategicMergePatchType, body, metav1.PatchOptions{}, "resize")
	if err != nil {
		return fmt.Errorf("failed to resize Pod %s/%s: %w", namespace, name, err)
	}
	return nil
}

func deploymentResourcePresence(object map[string]any) []bool {
	spec, _ := object["spec"].(map[string]any)
	template, _ := spec["template"].(map[string]any)
	podSpec, _ := template["spec"].(map[string]any)
	containers, _ := podSpec["containers"].([]any)
	presence := make([]bool, 0, len(containers))
	for _, item := range containers {
		container, _ := item.(map[string]any)
		resources, exists := container["resources"]
		presence = append(presence, exists && resources != nil)
	}
	return presence
}

func firstContainerResourcesPresent(object map[string]any) bool {
	spec, _ := object["spec"].(map[string]any)
	containers, _ := spec["containers"].([]any)
	if len(containers) == 0 {
		return false
	}
	container, _ := containers[0].(map[string]any)
	resources, exists := container["resources"]
	return exists && resources != nil
}
