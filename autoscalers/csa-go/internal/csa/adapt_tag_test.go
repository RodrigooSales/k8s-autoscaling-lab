package csa

import (
	"bytes"
	"encoding/json"
	"errors"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func TestAdaptTagUsesContractAndPatchesDeploymentImage(t *testing.T) {
	contract, api := loadAdaptationContract(t, "adapt_tag_success")
	stdout, stderr, exitCode := runTagCLI(t, api, contract.Input)
	if exitCode != 0 {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	assertJSONEqual(t, contract.ExpectedResult, stdout.Bytes())
	wantCalls := []string{"deployment:work/target", "status:system/adapter", "status:system/adapter", "patch-deployment:work/target"}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	var body map[string]any
	if err := json.Unmarshal(api.patchedDeployment, &body); err != nil {
		t.Fatal(err)
	}
	containers := body["spec"].(map[string]any)["template"].(map[string]any)["spec"].(map[string]any)["containers"].([]any)
	if got := containers[0].(map[string]any)["image"]; got != "registry.k8s.lab/csa-znn:400k" {
		t.Fatalf("patched image = %v", got)
	}
	if !strings.Contains(stderr.String(), "Adapting tag to 400k") {
		t.Fatalf("missing tag log: %q", stderr.String())
	}
}

func TestAdaptTagStoresInitialTagBeforeChangingImage(t *testing.T) {
	api := tagTestAPI("registry.k8s.lab/csa-znn:200k", "")
	api.statuses = []map[string]any{nil, nil, nil}
	stdout, _, exitCode := runTagCLI(t, api, tagAdapterInput(`true`, ""))
	if exitCode != 0 || stdout.String() != `{"tag":"400k"}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
	wantCalls := []string{
		"deployment:work/target", "status:system/adapter", "pod:system/adapter", "status:system/adapter",
		"patch:system/adapter", "status:system/adapter", "patch-deployment:work/target",
	}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	if api.patchedPod == nil || api.patchedPod.Annotations[initialDataAnnotation] != `{"tag":"200k"}` {
		t.Fatalf("initial tag annotation = %#v", api.patchedPod)
	}
}

func TestImageReferenceParsingMatchesSupportedPythonForms(t *testing.T) {
	tests := []struct {
		image, repository, name, tag string
		valid, hasTag                bool
	}{
		{image: "znn:200k", name: "znn", tag: "200k", valid: true, hasTag: true},
		{image: "team/znn:200k", name: "team/znn", tag: "200k", valid: true, hasTag: true},
		{image: "registry.k8s.lab/csa-znn:vq", name: "registry.k8s.lab/csa-znn", tag: "vq", valid: true, hasTag: true},
		{image: "registry.example/team/znn:200k", repository: "registry.example", name: "team/znn", tag: "200k", valid: true, hasTag: true},
		{image: "localhost:5000/team/znn:200k", repository: "localhost:5000", name: "team/znn", tag: "200k", valid: true, hasTag: true},
		{image: "/znn:200k", name: "znn", tag: "200k", valid: true, hasTag: true},
		{image: "znn", name: "znn", valid: true},
		{image: "ZNn:200k", valid: false},
		{image: "team//znn:200k", valid: false},
	}
	for _, test := range tests {
		t.Run(test.image, func(t *testing.T) {
			parts, ok := imageReferenceParts(test.image)
			if ok != test.valid {
				t.Fatalf("imageReferenceParts(%q) valid=%v, want %v", test.image, ok, test.valid)
			}
			if !ok {
				return
			}
			if parts.repository != test.repository || parts.image != test.name || parts.tag != test.tag || parts.hasTag != test.hasTag {
				t.Fatalf("parts = %#v", parts)
			}
		})
	}
}

func TestReplaceImageTagAndAdjacentTagBoundaries(t *testing.T) {
	if got := replaceImageTag("registry.k8s.lab/csa-znn:200k", "400k"); got != "registry.k8s.lab/csa-znn:400k" {
		t.Fatalf("replaceImageTag() = %q", got)
	}
	if got := replaceImageTag("invalid image", "400k"); got != "invalid image" {
		t.Fatalf("invalid image changed to %q", got)
	}
	for _, test := range []struct {
		current string
		up      bool
		want    string
		ok      bool
	}{{"100k", true, "200k", true}, {"400k", false, "200k", true}, {"100k", false, "", false}, {"800k", true, "", false}, {"300k", true, "", false}} {
		got, ok := adjacentTag(test.current, test.up)
		if got != test.want || ok != test.ok {
			t.Errorf("adjacentTag(%q, %v) = %q, %v", test.current, test.up, got, ok)
		}
	}
}

func TestAdaptTagSkipsAtInitialTagAndAtLadderBoundaries(t *testing.T) {
	t.Run("initial tag is upper bound", func(t *testing.T) {
		_, api := loadAdaptationContract(t, "adapt_tag_success")
		var stdout, stderr bytes.Buffer
		api.deployment.object.Spec.Template.Spec.Containers[0].Image = "registry.k8s.lab/csa-znn:100k"
		input := tagAdapterInput(`true`, "")
		exitCode := runTagMode(t, api, input, &stdout, &stderr)
		if exitCode != 0 || stdout.String() != `{"result":"skip"}` {
			t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
		}
		if !reflect.DeepEqual(api.calls, []string{"deployment:work/target", "status:system/adapter", "status:system/adapter"}) {
			t.Fatalf("calls = %#v", api.calls)
		}
	})

	t.Run("lowest tag cannot move down", func(t *testing.T) {
		api := tagTestAPI("registry.k8s.lab/csa-znn:100k", `{"tag":"200k"}`)
		stdout, _, exitCode := runTagCLI(t, api, tagAdapterInput(`false`, ""))
		if exitCode != 0 || stdout.String() != `{"result":"skip"}` {
			t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
		}
		if !reflect.DeepEqual(api.calls, []string{"deployment:work/target", "status:system/adapter"}) {
			t.Fatalf("calls = %#v", api.calls)
		}
	})
}

func TestAdaptTagRolloutMissingParameterAndContainerBehaviors(t *testing.T) {
	t.Run("rollout", func(t *testing.T) {
		api := tagTestAPI("znn:200k", `{"tag":"100k"}`)
		api.deployment.object.Status.UpdatedReplicas = 0
		stdout, _, exitCode := runTagCLI(t, api, tagAdapterInput(`true`, ""))
		if exitCode != 0 || stdout.String() != `{"result":"skip"}` {
			t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
		}
		if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
			t.Fatalf("calls = %#v", api.calls)
		}
	})

	t.Run("tag_up absent", func(t *testing.T) {
		api := tagTestAPI("znn:200k", `{"tag":"100k"}`)
		stdout, _, exitCode := runTagCLI(t, api, tagAdapterInput("", ""))
		if exitCode != 0 || stdout.Len() != 0 || !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
			t.Fatalf("exit=%d stdout=%q calls=%#v", exitCode, stdout.String(), api.calls)
		}
	})

	t.Run("znn absent", func(t *testing.T) {
		api := tagTestAPI("nginx:latest", `{"tag":"100k"}`)
		api.deployment.object.Spec.Template.Spec.Containers[0].Name = "worker"
		stdout, _, exitCode := runTagCLI(t, api, tagAdapterInput(`true`, ""))
		if exitCode != 0 || stdout.Len() != 0 || !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
			t.Fatalf("exit=%d stdout=%q calls=%#v", exitCode, stdout.String(), api.calls)
		}
	})
}

func TestAdaptTagUpdateCPUContinuesWhenThereAreNoPods(t *testing.T) {
	api := tagTestAPI("registry.k8s.lab/csa-znn:200k", `{"tag":"100k","cpu_limit":500}`)
	stdout, stderr, exitCode := runTagCLI(t, api, tagAdapterInput(`false`, `,"update_cpu":true`))
	if exitCode != 0 || stdout.String() != `{"tag":"100k"}` {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	wantCalls := []string{"deployment:work/target", "status:system/adapter", "status:system/adapter", "pods:work:app=demo", "patch-deployment:work/target"}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	if !strings.Contains(stderr.String(), "Could not read current mcpu") {
		t.Fatalf("missing empty CPU log: %q", stderr.String())
	}
}

func TestAdaptTagInvalidImageWritesErrorWithCriticalLog(t *testing.T) {
	api := tagTestAPI("ZNn:200k", `{"tag":"100k"}`)
	stdout, stderr, exitCode := runTagCLI(t, api, tagAdapterInput(`true`, ""))
	if exitCode != 0 || stdout.String() != `{"result":"error"}` {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	if !strings.Contains(stderr.String(), "CRITICAL") || !strings.Contains(stderr.String(), "Could not identify tag") {
		t.Fatalf("missing critical tag log: %q", stderr.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func TestAdaptTagImageWithoutTagReturnsError(t *testing.T) {
	api := tagTestAPI("registry.k8s.lab/csa-znn", `{"tag":"100k"}`)
	stdout, stderr, exitCode := runTagCLI(t, api, tagAdapterInput(`true`, ""))
	if exitCode != 0 || stdout.String() != `{"result":"error"}` {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	if !strings.Contains(stderr.String(), "CRITICAL") || !strings.Contains(stderr.String(), "Could not identify tag") {
		t.Fatalf("missing critical tag log: %q", stderr.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func TestAdaptTagUpdateCPUResizesDeploymentContainersBeforePatch(t *testing.T) {
	api := tagTestAPI("registry.k8s.lab/csa-znn:200k", `{"tag":"400k","cpu_limit":500}`)
	api.pods.items = []podSnapshot{podWithNameAndCPU("worker-1", "600m"), podWithNameAndCPU("worker-2", "700m")}
	stdout, _, exitCode := runTagCLI(t, api, tagAdapterInput(`false`, `,"update_cpu":true`))
	if exitCode != 0 || stdout.String() != `{"tag":"100k"}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
	wantCalls := []string{"deployment:work/target", "status:system/adapter", "status:system/adapter", "pods:work:app=demo", "patch-deployment:work/target"}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	if len(api.resizedPods) != 0 {
		t.Fatalf("tag update used Pod resize: %#v", api.resizedPods)
	}
	var body map[string]any
	if err := json.Unmarshal(api.patchedDeployment, &body); err != nil {
		t.Fatal(err)
	}
	containers := body["spec"].(map[string]any)["template"].(map[string]any)["spec"].(map[string]any)["containers"].([]any)
	for _, item := range containers {
		container := item.(map[string]any)
		limits := container["resources"].(map[string]any)["limits"].(map[string]any)
		if limits["cpu"] != "700m" {
			t.Errorf("container %s cpu = %v", container["name"], limits["cpu"])
		}
	}
	if got := containers[0].(map[string]any)["image"]; got != "registry.k8s.lab/csa-znn:100k" {
		t.Errorf("image = %v", got)
	}
}

func TestAdaptTagPatchFailureReturnsErrorResult(t *testing.T) {
	api := tagTestAPI("znn:200k", `{"tag":"100k"}`)
	api.callErrors = map[string]error{"patch-deployment": errors.New("forbidden")}
	stdout, _, exitCode := runTagCLI(t, api, tagAdapterInput(`true`, ""))
	if exitCode != 0 || stdout.String() != `{"result":"error"}` {
		t.Fatalf("exit=%d stdout=%q", exitCode, stdout.String())
	}
}

func runTagCLI(t *testing.T, api *recordingKubernetesAPI, input []byte) (*bytes.Buffer, *bytes.Buffer, int) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	exitCode := runTagMode(t, api, input, &stdout, &stderr)
	return &stdout, &stderr, exitCode
}

func runTagMode(t *testing.T, api *recordingKubernetesAPI, input []byte, stdout, stderr *bytes.Buffer) int {
	t.Helper()
	return runWithDependencies(
		[]string{"-m", "adapt_tag"}, bytes.NewReader(input), stdout, stderr, filepath.Join(t.TempDir(), "unused.yaml"),
		filepath.Join(t.TempDir(), "adapter.log"), func() (kubernetesAPI, error) { return api, nil }, cpuTestEnv,
	)
}

func tagTestAPI(image, initialData string) *recordingKubernetesAPI {
	deployment := &appsv1.Deployment{
		ObjectMeta: metav1.ObjectMeta{Name: "target", Namespace: "work", Generation: 1},
		Spec: appsv1.DeploymentSpec{
			Replicas: int32Pointer(1),
			Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "demo"}},
			Template: corev1.PodTemplateSpec{Spec: corev1.PodSpec{Containers: []corev1.Container{
				{Name: "znn", Image: image, Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("500m")}}},
				{Name: "nginx", Image: "nginx:latest", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("500m")}}},
			}}},
		},
		Status: appsv1.DeploymentStatus{ObservedGeneration: 1, UpdatedReplicas: 1, AvailableReplicas: 1},
	}
	return &recordingKubernetesAPI{
		deployment: &deploymentSnapshot{object: deployment, containerResourcesPresent: []bool{true, true}},
		pods:       &podListSnapshot{},
		selfPod:    &corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: "adapter", Namespace: "system", Annotations: map[string]string{}}},
		statuses:   []map[string]any{{"initialData": initialData}, {"initialData": initialData}, {"initialData": initialData}},
	}
}

func tagAdapterInput(tagUp, extra string) []byte {
	parameters := ""
	if tagUp != "" {
		parameters = `"tag_up":` + tagUp
	}
	if extra != "" && parameters != "" {
		parameters += extra
	}
	return []byte(`{"resource":{"metadata":{"name":"target","namespace":"work"}},"evaluation":{"parameters":{` + parameters + `}}}`)
}
