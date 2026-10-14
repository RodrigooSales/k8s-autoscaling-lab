package main

import (
	"context"
	"fmt"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/dynamic"
)

type replicaAdapter struct {
	client         dynamic.Interface
	namespace      string
	deploymentName string
}

func (adapter replicaAdapter) adapt(ctx context.Context, replicas int32) error {
	body := []byte(fmt.Sprintf(`{"spec":{"replicas":%d}}`, replicas))
	_, err := adapter.client.Resource(deploymentResource).
		Namespace(adapter.namespace).
		Patch(ctx, adapter.deploymentName, types.StrategicMergePatchType, body, metav1.PatchOptions{})
	if err != nil {
		return fmt.Errorf("failed to patch Deployment %s/%s replicas: %w", adapter.namespace, adapter.deploymentName, err)
	}
	return nil
}
