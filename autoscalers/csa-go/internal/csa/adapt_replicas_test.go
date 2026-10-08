package csa

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func TestAdaptReplicasPatchesLoadedDeploymentAndWritesReplicaCount(t *testing.T) {
	api := evaluationAPI(nil)
	fixtureBytes, err := os.ReadFile(filepath.Join("..", "..", "..", "contracts", "cases", "adapt_replicas_success.json"))
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Input json.RawMessage `json:"input"`
		State struct {
			Deployment json.RawMessage `json:"deployment"`
		} `json:"state"`
		ExpectedResult json.RawMessage `json:"expectedResult"`
	}
	if err := json.Unmarshal(fixtureBytes, &fixture); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(fixture.State.Deployment, api.deployment.object); err != nil {
		t.Fatal(err)
	}
	stdout, stderr, exitCode := runReplicaCLI(t, api, string(fixture.Input))

	if exitCode != 0 {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	assertJSONEqual(t, fixture.ExpectedResult, stdout.Bytes())
	if strings.HasSuffix(stdout.String(), "\n") {
		t.Fatalf("stdout has trailing newline: %q", stdout.String())
	}
	wantCalls := []string{"deployment:work/target", "patch-deployment:work/target"}
	if !reflect.DeepEqual(api.calls, wantCalls) {
		t.Fatalf("calls = %#v, want %#v", api.calls, wantCalls)
	}
	var body map[string]any
	decoder := json.NewDecoder(bytes.NewReader(api.patchedDeployment))
	decoder.UseNumber()
	if err := decoder.Decode(&body); err != nil {
		t.Fatal(err)
	}
	spec := body["spec"].(map[string]any)
	if spec["replicas"] != json.Number("5") {
		t.Fatalf("patched replicas = %#v", spec["replicas"])
	}
	metadata := body["metadata"].(map[string]any)
	if metadata["name"] != "target" || metadata["namespace"] != "work" {
		t.Fatalf("patch lost Deployment identity: %#v", metadata)
	}
	template := spec["template"].(map[string]any)
	podSpec := template["spec"].(map[string]any)
	container := podSpec["containers"].([]any)[0].(map[string]any)
	if container["image"] != "znn:200k" {
		t.Fatalf("patch lost loaded Deployment fields: %#v", container)
	}
	if !strings.Contains(stderr.String(), "Starting adapt_replicas script") || !strings.Contains(stderr.String(), "Scaling to 5 replicas") {
		t.Fatalf("missing adapter logs: %q", stderr.String())
	}
}

func TestAdaptReplicasRejectsNonIntegerValuesAfterDeploymentRead(t *testing.T) {
	for _, replicas := range []string{`null`, `"5"`, `5.0`, `1e0`} {
		t.Run(replicas, func(t *testing.T) {
			api := evaluationAPI(nil)
			stdout, stderr, exitCode := runReplicaCLI(t, api, replicaAdapterInput(replicas))
			if exitCode != 0 || stdout.Len() != 0 {
				t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
			}
			if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
				t.Fatalf("calls = %#v", api.calls)
			}
			if !strings.Contains(stderr.String(), "Parameters must include integer 'replicas'") {
				t.Fatalf("missing validation log: %q", stderr.String())
			}
		})
	}
}

func TestAdaptReplicasPreservesPythonBooleanIntegerBehavior(t *testing.T) {
	api := evaluationAPI(nil)
	stdout, stderr, exitCode := runReplicaCLI(t, api, replicaAdapterInput(`true`))
	if exitCode != 0 || stdout.String() != `{"replicas":true}` {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	var body map[string]any
	if err := json.Unmarshal(api.patchedDeployment, &body); err != nil {
		t.Fatal(err)
	}
	if body["spec"].(map[string]any)["replicas"] != true {
		t.Fatalf("patched replicas = %#v", body["spec"].(map[string]any)["replicas"])
	}
	if !strings.Contains(stderr.String(), "Scaling to True replicas") {
		t.Fatalf("Python-style bool log missing: %q", stderr.String())
	}
}

func TestAdaptReplicasWritesErrorResultWhenPatchFails(t *testing.T) {
	api := evaluationAPI(nil)
	api.callErrors = map[string]error{"patch-deployment": errors.New("forbidden")}
	stdout, stderr, exitCode := runReplicaCLI(t, api, replicaAdapterInput(`3`))
	if exitCode != 0 || stdout.String() != `{"result":"error"}` {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	if !strings.Contains(stderr.String(), "Failed to patch Deployment work/target") {
		t.Fatalf("missing patch error log: %q", stderr.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target", "patch-deployment:work/target"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func TestAdaptReplicasInvalidContextAndReadFailureProduceNoOutput(t *testing.T) {
	t.Run("missing identity", func(t *testing.T) {
		var stdout, stderr bytes.Buffer
		factoryCalls := 0
		exitCode := runWithDependencies([]string{"-m", "adapt_replicas"}, strings.NewReader(`{}`), &stdout, &stderr,
			"/missing/config.yaml", filepath.Join(t.TempDir(), "adapter.log"), func() (kubernetesAPI, error) {
				factoryCalls++
				return evaluationAPI(nil), nil
			}, func(string) string { return "" })
		if exitCode != 0 || stdout.Len() != 0 || factoryCalls != 0 {
			t.Fatalf("exit=%d stdout=%q factory calls=%d stderr=%q", exitCode, stdout.String(), factoryCalls, stderr.String())
		}
	})

	t.Run("deployment read error", func(t *testing.T) {
		api := evaluationAPI(nil)
		api.callErrors = map[string]error{"deployment": errors.New("forbidden")}
		stdout, stderr, exitCode := runReplicaCLI(t, api, replicaAdapterInput(`2`))
		if exitCode != 0 || stdout.Len() != 0 {
			t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
		}
		if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
			t.Fatalf("calls = %#v", api.calls)
		}
	})
}

func TestAdaptReplicasMissingParametersProducesEmptyOutput(t *testing.T) {
	api := evaluationAPI(nil)
	stdout, stderr, exitCode := runReplicaCLI(t, api, `{"resource":{"metadata":{"name":"target","namespace":"work"}}}`)
	if exitCode != 0 || stdout.Len() != 0 {
		t.Fatalf("exit=%d stdout=%q stderr=%q", exitCode, stdout.String(), stderr.String())
	}
	if !reflect.DeepEqual(api.calls, []string{"deployment:work/target"}) {
		t.Fatalf("calls = %#v", api.calls)
	}
}

func runReplicaCLI(t *testing.T, api *recordingKubernetesAPI, input string) (*bytes.Buffer, *bytes.Buffer, int) {
	t.Helper()
	var stdout, stderr bytes.Buffer
	exitCode := runWithDependencies(
		[]string{"-m", "adapt_replicas"}, strings.NewReader(input), &stdout, &stderr,
		filepath.Join(t.TempDir(), "missing.yaml"), filepath.Join(t.TempDir(), "adapter.log"),
		func() (kubernetesAPI, error) { return api, nil }, func(string) string { return "" },
	)
	return &stdout, &stderr, exitCode
}

func replicaAdapterInput(value string) string {
	return `{"resource":{"metadata":{"name":"target","namespace":"work"}},"evaluation":{"parameters":{"replicas":` + value + `}}}`
}
