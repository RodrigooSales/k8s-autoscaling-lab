package csa

import (
	"context"
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type recordingKubernetesAPI struct {
	calls             []string
	deployment        *deploymentSnapshot
	pods              *podListSnapshot
	podLists          []*podListSnapshot
	podListIndex      int
	selfPod           *corev1.Pod
	statuses          []map[string]any
	statusIndex       int
	callErrors        map[string]error
	patchedPod        *corev1.Pod
	patchedDeployment []byte
	resizedPods       []recordedPodResize
}

type recordedPodResize struct {
	name      string
	namespace string
	body      []byte
}

func (api *recordingKubernetesAPI) readDeployment(_ context.Context, name, namespace string) (*deploymentSnapshot, error) {
	api.calls = append(api.calls, "deployment:"+namespace+"/"+name)
	if err := api.callErrors["deployment"]; err != nil {
		return nil, err
	}
	return api.deployment, nil
}

func (api *recordingKubernetesAPI) listPods(_ context.Context, namespace, selector string) (*podListSnapshot, error) {
	api.calls = append(api.calls, "pods:"+namespace+":"+selector)
	if err := api.callErrors["pods"]; err != nil {
		return nil, err
	}
	if api.podListIndex < len(api.podLists) {
		pods := api.podLists[api.podListIndex]
		api.podListIndex++
		return pods, nil
	}
	return api.pods, nil
}

func (api *recordingKubernetesAPI) readPod(_ context.Context, name, namespace string) (*corev1.Pod, error) {
	api.calls = append(api.calls, "pod:"+namespace+"/"+name)
	if err := api.callErrors["pod"]; err != nil {
		return nil, err
	}
	return api.selfPod, nil
}

func (api *recordingKubernetesAPI) getCSAStatus(_ context.Context, name, namespace string) (map[string]any, error) {
	api.calls = append(api.calls, "status:"+namespace+"/"+name)
	if err := api.callErrors["status"]; err != nil {
		return nil, err
	}
	if api.statusIndex >= len(api.statuses) {
		return nil, nil
	}
	status := api.statuses[api.statusIndex]
	api.statusIndex++
	return status, nil
}

func (api *recordingKubernetesAPI) patchPod(_ context.Context, name, namespace string, pod *corev1.Pod) error {
	api.calls = append(api.calls, "patch:"+namespace+"/"+name)
	if err := api.callErrors["patch"]; err != nil {
		return err
	}
	api.patchedPod = pod.DeepCopy()
	return nil
}

func (api *recordingKubernetesAPI) patchDeployment(_ context.Context, name, namespace string, body []byte) error {
	api.calls = append(api.calls, "patch-deployment:"+namespace+"/"+name)
	if err := api.callErrors["patch-deployment"]; err != nil {
		return err
	}
	api.patchedDeployment = append([]byte(nil), body...)
	return nil
}

func (api *recordingKubernetesAPI) resizePod(_ context.Context, name, namespace string, body []byte) error {
	api.calls = append(api.calls, "resize:"+namespace+"/"+name)
	api.resizedPods = append(api.resizedPods, recordedPodResize{name: name, namespace: namespace, body: append([]byte(nil), body...)})
	if err := api.callErrors["resize:"+name]; err != nil {
		return err
	}
	return api.callErrors["resize"]
}

func TestInitialDataGetStoredCPUReadsStatusAndParsesJSON(t *testing.T) {
	api := &recordingKubernetesAPI{statuses: []map[string]any{{"initialData": `{"cpu_limit":150,"tag":"200k"}`}}}
	store, stderr := newTestInitialDataStore(t, api, map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})

	cpu, err := store.getStoredCPU()
	if err != nil || cpu != 150 {
		t.Fatalf("getStoredCPU() = %d, %v; want 150", cpu, err)
	}
	if !reflect.DeepEqual(api.calls, []string{"status:system/adapter"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
	if stderr.Len() != 0 {
		t.Fatalf("unexpected log output: %q", stderr.String())
	}
}

func TestInitialDataStoreCPURepeatsReadsAndMergesStatusData(t *testing.T) {
	api := &recordingKubernetesAPI{
		statuses: []map[string]any{
			{},
			{"initialData": `{"tag":"200k"}`},
		},
		selfPod: &corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: "adapter", Namespace: "system", Annotations: map[string]string{"existing": "kept"}}},
	}
	store, stderr := newTestInitialDataStore(t, api, map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})
	if err := store.storeCPU(int64(175)); err != nil {
		t.Fatal(err)
	}

	wantCalls := []string{"status:system/adapter", "pod:system/adapter", "status:system/adapter", "patch:system/adapter"}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	if api.patchedPod == nil {
		t.Fatal("expected Pod patch")
	}
	if api.patchedPod.Annotations[initialDataAnnotation] != `{"cpu_limit":175,"tag":"200k"}` {
		t.Fatalf("initialData annotation = %q", api.patchedPod.Annotations[initialDataAnnotation])
	}
	if api.patchedPod.Annotations["existing"] != "kept" {
		t.Fatalf("existing annotation was lost: %#v", api.patchedPod.Annotations)
	}
	if stderr.Len() == 0 {
		t.Fatal("expected initial-data events in logger output")
	}
}

func TestInitialDataStoreCPUPreservesMissingAnnotationsBehavior(t *testing.T) {
	api := &recordingKubernetesAPI{
		statuses: []map[string]any{{}, {}, {}},
		selfPod:  &corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: "adapter", Namespace: "system"}},
	}
	store, _ := newTestInitialDataStore(t, api, map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})
	if err := store.storeCPU(int64(175)); err != nil {
		t.Fatal(err)
	}
	if api.patchedPod == nil {
		t.Fatal("expected full Pod patch")
	}
	if api.patchedPod.Annotations != nil {
		t.Fatalf("annotations = %#v, want nil as in Python reference behavior", api.patchedPod.Annotations)
	}
}

func TestInitialDataRejectsMalformedStatusJSON(t *testing.T) {
	api := &recordingKubernetesAPI{statuses: []map[string]any{{"initialData": "{"}}}
	store, _ := newTestInitialDataStore(t, api, map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})
	if _, err := store.getStoredCPU(); err == nil {
		t.Fatal("getStoredCPU accepted malformed initialData JSON")
	}
}

func TestInitialDataMissingIdentityDoesNotCallKubernetes(t *testing.T) {
	api := &recordingKubernetesAPI{}
	store, stderr := newTestInitialDataStore(t, api, nil)
	if err := store.storeCPU(int64(175)); err != nil {
		t.Fatal(err)
	}
	if len(api.calls) != 0 {
		t.Fatalf("calls = %#v, want none", api.calls)
	}
	if !strings.Contains(stderr.String(), "CSA_NAME or CSA_NAMESPACE not defined!") || !strings.Contains(stderr.String(), "Could not find CSA pod") {
		t.Fatalf("missing identity logs = %q", stderr.String())
	}
}

func TestInitialDataDoesNotOverwriteExistingCPU(t *testing.T) {
	api := &recordingKubernetesAPI{statuses: []map[string]any{{"initialData": `{"cpu_limit":200}`}}}
	store, _ := newTestInitialDataStore(t, api, map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})
	if err := store.storeCPU(int64(175)); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(api.calls, []string{"status:system/adapter"}) {
		t.Fatalf("calls = %#v, existing CPU should prevent writes", api.calls)
	}
}

func TestInitialDataPropagatesStatusAPIError(t *testing.T) {
	api := &recordingKubernetesAPI{callErrors: map[string]error{"status": errors.New("forbidden")}}
	store, _ := newTestInitialDataStore(t, api, map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})
	if _, err := store.getStoredCPU(); err == nil {
		t.Fatal("getStoredCPU swallowed Kubernetes status error")
	}
}

func newTestInitialDataStore(t *testing.T, api *recordingKubernetesAPI, env map[string]string) (*initialDataStore, *strings.Builder) {
	t.Helper()
	var stderr strings.Builder
	logPath := filepath.Join(t.TempDir(), "adapter.log")
	logger, err := newAdapterLogger("evaluate", logPath, &stderr)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := logger.close(); err != nil {
			t.Error(err)
		}
	})
	lookup := func(key string) string { return env[key] }
	return &initialDataStore{api: api, logger: logger, env: lookup}, &stderr
}

var _ kubernetesAPI = (*recordingKubernetesAPI)(nil)
