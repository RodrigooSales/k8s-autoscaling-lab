package csa

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func TestAdaptCPUUsesContractAndResizesEachListedPodInOrder(t *testing.T) {
	contract, api := loadAdaptationContract(t, "adapt_cpu_success")
	stdout, stderr, exitCode := runCPUCLI(t, api, contract.Input, "maxCPU: 750\n")
	if exitCode != 0 {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	assertJSONEqual(t, contract.ExpectedResult, stdout.Bytes())
	wantCalls := []string{
		"deployment:work/target", "status:system/adapter", "pods:work:app=demo", "pods:work:app=demo",
		"resize:work/worker-1", "resize:work/worker-2",
	}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	if len(api.resizedPods) != 2 {
		t.Fatalf("resize calls = %d, want 2", len(api.resizedPods))
	}
	wantBody := []byte(`{"spec":{"containers":[{"name":"znn","resources":{"limits":{"cpu":"630m"}}},{"name":"nginx","resources":{"limits":{"cpu":"630m"}}}]}}`)
	for _, resize := range api.resizedPods {
		assertJSONEqual(t, wantBody, resize.body)
	}
	if !strings.Contains(stderr.String(), "Calculated new_mcpu 630") || !strings.Contains(stderr.String(), "Scaled cpu to 630") {
		t.Fatalf("missing CPU adaptation logs: %q", stderr.String())
	}
}

func TestAdaptCPUUsesPythonRoundingAndBounds(t *testing.T) {
	tests := []struct {
		name                   string
		current, baseline, max int64
		multiplier             float64
		want                   int64
	}{
		{name: "half to even down", current: 125, multiplier: 0.5, baseline: 1, max: 750, want: 62},
		{name: "half to even up", current: 127, multiplier: 0.5, baseline: 1, max: 750, want: 64},
		{name: "maximum CPU", current: 700, multiplier: 2, baseline: 500, max: 750, want: 750},
		{name: "initial CPU floor", current: 600, multiplier: 0.5, baseline: 500, max: 750, want: 500},
		{name: "large finite multiplier caps at maximum", current: 700, multiplier: 1e300, baseline: 500, max: 750, want: 750},
		{name: "large negative multiplier caps at initial CPU", current: 700, multiplier: -1e300, baseline: 500, max: 750, want: 500},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			got, err := adjustedCPUMilli(test.current, test.multiplier, test.baseline, test.max)
			if err != nil || got != test.want {
				t.Fatalf("adjustedCPUMilli() = %d, %v; want %d", got, err, test.want)
			}
		})
	}
}

func TestAdaptCPULogsTheAppliedCaps(t *testing.T) {
	api := cpuTestAPI()
	stdout, stderr, exitCode := runCPUCLI(t, api, cpuAdapterInput(`2`), "maxCPU: 650\n")
	if exitCode != 0 || stdout.String() != `{"cpu":650}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
	if !strings.Contains(stderr.String(), "new_mcpu capped to 650") {
		t.Fatalf("missing cap log: %q", stderr.String())
	}
}

func TestAdaptCPURejectsInvalidMultiplierWithoutListingPods(t *testing.T) {
	for _, multiplier := range []string{`null`, `"0.8"`, `{}`} {
		t.Run(multiplier, func(t *testing.T) {
			api := cpuTestAPI()
			stdout, _, exitCode := runCPUCLI(t, api, cpuAdapterInput(multiplier), "maxCPU: 750\n")
			if exitCode != 0 || stdout.String() != `{"result":"error"}` {
				t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
			}
			if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
				t.Fatalf("calls = %#v", api.calls)
			}
		})
	}
}

func TestAdaptCPUSkipsRolloutBeforeReadingConfigOrState(t *testing.T) {
	api := cpuTestAPI()
	api.deployment.object.Status.UpdatedReplicas = 1
	stdout, _, exitCode := runCPUCLI(t, api, cpuAdapterInput(`0.8`), "invalid: [yaml\n")
	if exitCode != 0 || stdout.String() != `{"result":"skip"}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func TestAdaptCPUReportsNoPodsAndStopsBeforeCurrentCPULookup(t *testing.T) {
	api := cpuTestAPI()
	api.pods = &podListSnapshot{}
	stdout, _, exitCode := runCPUCLI(t, api, cpuAdapterInput(`0.8`), "maxCPU: 750\n")
	if exitCode != 0 || stdout.String() != `{"result":"error"}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target", "status:system/adapter", "pods:work:app=demo"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func TestAdaptCPUStoresSpecBaselineAndAdaptsInTheSameInvocation(t *testing.T) {
	api := cpuTestAPI()
	api.statuses = []map[string]any{nil, nil, nil}
	stdout, stderr, exitCode := runCPUCLI(t, api, cpuAdapterInput(`0.9`), "maxCPU: 750\n")
	if exitCode != 0 || stdout.String() != `{"cpu":540}` {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	wantCalls := []string{
		"deployment:work/target", "status:system/adapter", "status:system/adapter", "pod:system/adapter",
		"status:system/adapter", "patch:system/adapter", "pods:work:app=demo", "pods:work:app=demo",
		"resize:work/worker-1",
	}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	if api.patchedPod == nil || api.patchedPod.Annotations[initialDataAnnotation] != `{"cpu_limit":500}` {
		t.Fatalf("initial CPU annotation = %#v", api.patchedPod)
	}
}

func TestAdaptCPUReturnsResultErrorWhenSecondPodListIsEmpty(t *testing.T) {
	api := cpuTestAPI()
	api.podLists = []*podListSnapshot{api.pods, {}}
	stdout, _, exitCode := runCPUCLI(t, api, cpuAdapterInput(`0.8`), "maxCPU: 750\n")
	if exitCode != 0 || stdout.String() != `{"result":"error"}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
	if len(api.resizedPods) != 0 {
		t.Fatalf("resizes = %#v, want none", api.resizedPods)
	}
}

func TestAdaptCPUStopsAfterTheFirstFailedResize(t *testing.T) {
	api := cpuTestAPI()
	api.pods.items = append(api.pods.items, podWithNameAndCPU("worker-2", "700m"))
	api.callErrors = map[string]error{"resize:worker-2": errors.New("resize rejected")}
	stdout, _, exitCode := runCPUCLI(t, api, cpuAdapterInput(`0.9`), "maxCPU: 750\n")
	if exitCode != 0 || stdout.String() != `{"result":"error"}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
	if got := api.calls[len(api.calls)-2:]; !reflect.DeepEqual(got, []string{"resize:work/worker-1", "resize:work/worker-2"}) {
		t.Fatalf("resize sequence = %#v", got)
	}
	if len(api.resizedPods) != 2 {
		t.Fatalf("attempted resizes = %d, want 2 and no rollback", len(api.resizedPods))
	}
}

func TestAdaptCPUReturnsErrorForPodsWithoutUsableCPULimits(t *testing.T) {
	api := cpuTestAPI()
	api.pods = &podListSnapshot{items: []podSnapshot{{object: corev1.Pod{}}}}
	stdout, _, exitCode := runCPUCLI(t, api, cpuAdapterInput(`0.8`), "maxCPU: 750\n")
	if exitCode != 1 || stdout.Len() != 0 {
		t.Fatalf("exit=%d stdout=%q; Python max() on an empty CPU sequence raises", exitCode, stdout.String())
	}
}

func TestAdaptCPUReturnsResultErrorWhenCurrentCPULimitIsZero(t *testing.T) {
	api := cpuTestAPI()
	api.pods.items[0].firstContainerResourcesPresent = true
	api.pods.items[0].object.Spec.Containers[0].Resources.Limits = corev1.ResourceList{}
	stdout, _, exitCode := runCPUCLI(t, api, cpuAdapterInput(`0.8`), "maxCPU: 750\n")
	if exitCode != 0 || stdout.String() != `{"result":"error"}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
	if len(api.resizedPods) != 0 {
		t.Fatalf("resizes = %#v, want none", api.resizedPods)
	}
}

func runCPUCLI(t *testing.T, api *recordingKubernetesAPI, input []byte, config string) (*bytes.Buffer, *bytes.Buffer, int) {
	t.Helper()
	configPath := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(configPath, []byte(config), 0o600); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	exitCode := runWithDependencies(
		[]string{"-m", "adapt_cpu"}, bytes.NewReader(input), &stdout, &stderr, configPath,
		filepath.Join(t.TempDir(), "adapter.log"), func() (kubernetesAPI, error) { return api, nil }, cpuTestEnv,
	)
	return &stdout, &stderr, exitCode
}

func cpuTestAPI() *recordingKubernetesAPI {
	deployment := &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{Name: "target", Namespace: "work", Generation: 1},
		Spec: appsv1.DeploymentSpec{
			Replicas: int32Pointer(2),
			Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "demo"}},
			Template: corev1.PodTemplateSpec{Spec: corev1.PodSpec{Containers: []corev1.Container{
				{Name: "znn", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("500m")}}},
				{Name: "nginx", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("500m")}}},
			}}},
		},
		Status: appsv1.DeploymentStatus{ObservedGeneration: 1, UpdatedReplicas: 2, AvailableReplicas: 2},
	}
	return &recordingKubernetesAPI{
		deployment: &deploymentSnapshot{object: deployment, containerResourcesPresent: []bool{true, true}},
		pods:       &podListSnapshot{items: []podSnapshot{podWithNameAndCPU("worker-1", "600m")}},
		selfPod:    &corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: "adapter", Namespace: "system", Annotations: map[string]string{}}},
		statuses:   []map[string]any{{"initialData": `{"cpu_limit":500}`}},
	}
}

func podWithNameAndCPU(name, cpu string) podSnapshot {
	return podSnapshot{object: corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: name}, Spec: corev1.PodSpec{Containers: []corev1.Container{{
		Name: "znn", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(cpu)}},
	}}}}, firstContainerResourcesPresent: true}
}

func cpuAdapterInput(multiplier string) []byte {
	return []byte(`{"resource":{"metadata":{"name":"target","namespace":"work"}},"evaluation":{"parameters":{"cpu_multiplier":` + multiplier + `}}}`)
}

func cpuTestEnv(key string) string {
	return map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"}[key]
}
