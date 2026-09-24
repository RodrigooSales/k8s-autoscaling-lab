package csa

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type adaptationContract struct {
	Input  json.RawMessage `json:"input"`
	Config map[string]any  `json:"config"`
	State  struct {
		Deployment  json.RawMessage   `json:"deployment"`
		Pods        []json.RawMessage `json:"pods"`
		InitialData string            `json:"initialData"`
	} `json:"state"`
	ExpectedResult json.RawMessage `json:"expectedResult"`
}

func loadAdaptationContract(t *testing.T, id string) (adaptationContract, *recordingKubernetesAPI) {
	t.Helper()
	path := filepath.Join("..", "..", "..", "contracts", "cases", id+".json")
	encoded, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var contract adaptationContract
	if err := json.Unmarshal(encoded, &contract); err != nil {
		t.Fatal(err)
	}

	var deployment appsv1.Deployment
	if err := json.Unmarshal(contract.State.Deployment, &deployment); err != nil {
		t.Fatal(err)
	}
	var deploymentMap map[string]any
	if err := json.Unmarshal(contract.State.Deployment, &deploymentMap); err != nil {
		t.Fatal(err)
	}
	api := &recordingKubernetesAPI{
		deployment: &deploymentSnapshot{object: &deployment, containerResourcesPresent: deploymentResourcePresence(deploymentMap)},
		pods:       &podListSnapshot{},
		selfPod:    &corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: "adapter", Namespace: "system", Annotations: map[string]string{}}},
	}
	for _, encodedPod := range contract.State.Pods {
		var pod corev1.Pod
		if err := json.Unmarshal(encodedPod, &pod); err != nil {
			t.Fatal(err)
		}
		var podMap map[string]any
		if err := json.Unmarshal(encodedPod, &podMap); err != nil {
			t.Fatal(err)
		}
		api.pods.items = append(api.pods.items, podSnapshot{
			object: pod, firstContainerResourcesPresent: firstContainerResourcesPresent(podMap),
		})
	}
	status := map[string]any(nil)
	if contract.State.InitialData != "" {
		status = map[string]any{"initialData": contract.State.InitialData}
	}
	api.statuses = []map[string]any{status, status, status}
	return contract, api
}

func int32Pointer(value int32) *int32 {
	return &value
}
