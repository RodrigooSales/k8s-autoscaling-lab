package csa

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"strings"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func TestEvaluateReadsDeploymentPodsAndStatusBeforeWritingDecision(t *testing.T) {
	api := evaluationAPI([]map[string]any{{"initialData": `{"cpu_limit":500}`}})
	stdout, stderr, exitCode := runEvaluation(t, api, evaluationInputJSON("950000", 2), "enabled_strategies:\n  - adapt_replicas\n  - adapt_cpu\n  - adapt_tag\nminReplicas: 1\nmaxReplicas: 5\nmaxCPU: 750\n", map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})

	if exitCode != 0 {
		t.Fatalf("exit = %d, stderr = %q", exitCode, stderr.String())
	}
	if got, want := stdout.String(), `{"strategy":"adapt_replicas","parameters":{"replicas":2}}`; got != want {
		t.Fatalf("stdout = %q, want %q", got, want)
	}
	wantCalls := []string{"deployment:work/target", "pods:work:app=demo", "status:system/adapter"}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	if !strings.Contains(stderr.String(), "Starting evaluate script") || !strings.Contains(stderr.String(), "eval_main") {
		t.Fatalf("missing evaluate logs: %q", stderr.String())
	}
}

func TestEvaluatePersistsMissingCPUAndUsesZeroBaselineThisCycle(t *testing.T) {
	api := evaluationAPI([]map[string]any{nil, nil, nil})
	api.selfPod.Annotations = map[string]string{}
	stdout, stderr, exitCode := runEvaluation(t, api, evaluationInputJSON("800000", 1), "enabled_strategies:\n  - adapt_replicas\n  - adapt_cpu\n  - adapt_tag\nminReplicas: 1\nmaxReplicas: 5\nmaxCPU: 750\n", map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})

	if exitCode != 0 {
		t.Fatalf("exit = %d, stderr = %q", exitCode, stderr.String())
	}
	if got, want := stdout.String(), `{"strategy":"adapt_cpu","parameters":{"cpu_multiplier":0.8}}`; got != want {
		t.Fatalf("stdout = %q, want %q", got, want)
	}
	wantCalls := []string{
		"deployment:work/target", "pods:work:app=demo", "status:system/adapter",
		"status:system/adapter", "pod:system/adapter", "status:system/adapter", "patch:system/adapter",
	}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	if api.patchedPod == nil || api.patchedPod.Annotations[initialDataAnnotation] != `{"cpu_limit":500}` {
		t.Fatalf("initialData patch = %#v", api.patchedPod)
	}
}

func TestEvaluateListsPodsForHorizontalOnlyConfiguration(t *testing.T) {
	api := evaluationAPI([]map[string]any{{"initialData": `{"cpu_limit":500}`}})
	_, stderr, exitCode := runEvaluation(t, api, evaluationInputJSON("940000", 2), "enabled_strategies:\n  - adapt_replicas\nminReplicas: 1\nmaxReplicas: 5\n", map[string]string{"CSA_NAME": "adapter", "CSA_NAMESPACE": "system"})
	if exitCode != 0 {
		t.Fatalf("exit = %d, stderr = %q", exitCode, stderr.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target", "pods:work:app=demo", "status:system/adapter"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func TestEvaluateLoadsDeploymentBeforeConfigAndReturnsEmptyForMalformedConfig(t *testing.T) {
	api := evaluationAPI(nil)
	stdout, stderr, exitCode := runEvaluation(t, api, evaluationInputJSON("950000", 2), "invalid: [yaml\n", nil)
	if exitCode != 0 {
		t.Fatalf("exit = %d, stderr = %q", exitCode, stderr.String())
	}
	if stdout.Len() != 0 {
		t.Fatalf("stdout = %q, want empty", stdout.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
		t.Fatalf("calls = %#v; deployment must be read before config", api.calls)
	}
}

func TestEvaluateEmptyPodsReturnsEmptyOutputWithoutReadingState(t *testing.T) {
	api := evaluationAPI(nil)
	api.pods = &podListSnapshot{}
	stdout, stderr, exitCode := runEvaluation(t, api, evaluationInputJSON("950000", 2), "enabled_strategies: [adapt_replicas]\n", nil)
	if exitCode != 0 || stdout.Len() != 0 {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target", "pods:work:app=demo"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func TestEvaluatePropagatesKubernetesFailuresInCallOrder(t *testing.T) {
	api := evaluationAPI(nil)
	api.callErrors = map[string]error{"pods": context.DeadlineExceeded}
	stdout, stderr, exitCode := runEvaluation(t, api, evaluationInputJSON("950000", 2), "enabled_strategies: [adapt_replicas]\n", nil)
	if exitCode != 1 || stdout.Len() != 0 {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	if !strings.Contains(stderr.String(), "context deadline exceeded") {
		t.Fatalf("stderr = %q", stderr.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target", "pods:work:app=demo"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func TestEvaluateRejectsPodsWithoutContainerResourcesLikePythonMax(t *testing.T) {
	api := evaluationAPI(nil)
	api.pods.items[0].firstContainerResourcesPresent = false
	stdout, stderr, exitCode := runEvaluation(t, api, evaluationInputJSON("950000", 2), "enabled_strategies: [adapt_replicas]\n", nil)
	if exitCode != 1 || stdout.Len() != 0 {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	if !strings.Contains(stderr.String(), "max() arg is an empty sequence") {
		t.Fatalf("stderr = %q", stderr.String())
	}
}

func TestCurrentPodMCPUUsesMaximumFirstContainerWithoutPhaseFiltering(t *testing.T) {
	pods := &podListSnapshot{items: []podSnapshot{
		{object: podWithCPULimit("300m", corev1.PodRunning), firstContainerResourcesPresent: true},
		{object: podWithCPULimit("650m", corev1.PodPending), firstContainerResourcesPresent: true},
	}}
	milli, found, err := currentPodMCPU(pods)
	if err != nil || !found || milli != 650 {
		t.Fatalf("currentPodMCPU() = %d, %v, %v; want 650, true, nil", milli, found, err)
	}
}

func TestEvaluateMissingConfigFileFailsAfterDeploymentRead(t *testing.T) {
	api := evaluationAPI(nil)
	var stdout, stderr bytes.Buffer
	logPath := filepath.Join(t.TempDir(), "adapter.log")
	exitCode := runWithDependencies(
		[]string{"-m", "evaluate"}, strings.NewReader(evaluationInputJSON("950000", 2)), &stdout, &stderr,
		filepath.Join(t.TempDir(), "missing.yaml"), logPath, func() (kubernetesAPI, error) { return api, nil }, func(string) string { return "" },
	)
	if exitCode != 1 || stdout.Len() != 0 {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func evaluationAPI(statuses []map[string]any) *recordingKubernetesAPI {
	return &recordingKubernetesAPI{
		deployment: &deploymentSnapshot{
			object: &appsv1.Deployment{
				ObjectMeta: metav1.ObjectMeta{Name: "target", Namespace: "work"},
				Spec: appsv1.DeploymentSpec{
					Selector: &metav1.LabelSelector{MatchLabels: map[string]string{"app": "demo"}},
					Template: corev1.PodTemplateSpec{Spec: corev1.PodSpec{Containers: []corev1.Container{{
						Name: "znn", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("500m")}},
					}}}},
				},
			},
			containerResourcesPresent: []bool{true},
		},
		pods: &podListSnapshot{items: []podSnapshot{
			{object: corev1.Pod{Spec: corev1.PodSpec{Containers: []corev1.Container{{
				Name: "znn", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse("500m")}},
			}}}}, firstContainerResourcesPresent: true},
		}},
		selfPod:  &corev1.Pod{ObjectMeta: metav1.ObjectMeta{Name: "adapter", Namespace: "system", Annotations: map[string]string{"keep": "yes"}}},
		statuses: statuses,
	}
}

func podWithCPULimit(cpu string, phase corev1.PodPhase) corev1.Pod {
	return corev1.Pod{Status: corev1.PodStatus{Phase: phase}, Spec: corev1.PodSpec{Containers: []corev1.Container{{
		Name: "znn", Resources: corev1.ResourceRequirements{Limits: corev1.ResourceList{corev1.ResourceCPU: resource.MustParse(cpu)}},
	}}}}
}

func runEvaluation(t *testing.T, api *recordingKubernetesAPI, input, config string, env map[string]string) (*bytes.Buffer, *bytes.Buffer, int) {
	t.Helper()
	configPath := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(configPath, []byte(config), 0o600); err != nil {
		t.Fatal(err)
	}
	var stdout, stderr bytes.Buffer
	logPath := filepath.Join(t.TempDir(), "adapter.log")
	lookup := func(key string) string { return env[key] }
	exitCode := runWithDependencies(
		[]string{"-m", "evaluate"}, strings.NewReader(input), &stdout, &stderr, configPath, logPath,
		func() (kubernetesAPI, error) { return api, nil }, lookup,
	)
	return &stdout, &stderr, exitCode
}

func evaluationInputJSON(current string, replicas int) string {
	return `{"resource":{"metadata":{"name":"target","namespace":"work"},"spec":{"replicas":` + strconv.Itoa(replicas) + `}},"metrics":[{"value":"{\"current_value\":\"` + current + `\",\"target_value\":\"1000\"}"}]}`
}
