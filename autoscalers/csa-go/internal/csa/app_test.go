package csa

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestInvalidCLIArgumentsReturnUsageError(t *testing.T) {
	for _, args := range [][]string{
		nil,
		{"metric"},
		{"-m"},
		{"--mode", "metric"},
		{"-m", "unknown"},
	} {
		t.Run(strings.Join(args, "_"), func(t *testing.T) {
			var stdout, stderr bytes.Buffer
			logPath := filepath.Join(t.TempDir(), "adapter.log")
			exitCode := run(args, strings.NewReader("{}"), &stdout, &stderr, "/missing/config.yaml", logPath)
			if exitCode != 2 {
				t.Fatalf("exit code = %d, want 2; stderr = %q", exitCode, stderr.String())
			}
			if stdout.Len() != 0 {
				t.Fatalf("unexpected stdout: %q", stdout.String())
			}
			if _, err := os.Stat(logPath); !os.IsNotExist(err) {
				t.Fatalf("invalid CLI created log file: stat error = %v", err)
			}
		})
	}
}

func TestMetricModeDoesNotLoadConfig(t *testing.T) {
	input := `{"resource":{"spec":{"replicas":2}},"kubernetesMetrics":[{"spec":{"external":{"target":{"value":"1000"}}},"external":{"current":{"value":"950000"}}}]}`
	want := `{"current_replicas":2,"target_value":"1000","current_value":"950000"}`
	var stdout, stderr bytes.Buffer
	logPath := filepath.Join(t.TempDir(), "adapter.log")
	exitCode := run(
		[]string{"-m", "metric"},
		strings.NewReader(input),
		&stdout,
		&stderr,
		filepath.Join(t.TempDir(), "missing-config.yaml"),
		logPath,
	)
	if exitCode != 0 {
		t.Fatalf("exit code = %d, stderr = %s", exitCode, stderr.String())
	}
	assertJSONEqual(t, []byte(want), stdout.Bytes())
	assertContains(t, stderr.String(), "Starting metric script")
	logBytes, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatal(err)
	}
	assertContains(t, string(logBytes), "Starting metric script")
}

func TestMalformedMetricInputReturnsExecutionError(t *testing.T) {
	var stdout, stderr bytes.Buffer
	exitCode := run(
		[]string{"-m", "metric"},
		strings.NewReader("{"),
		&stdout,
		&stderr,
		"/missing/config.yaml",
		filepath.Join(t.TempDir(), "adapter.log"),
	)
	if exitCode != 1 {
		t.Fatalf("exit code = %d, want 1; stderr = %q", exitCode, stderr.String())
	}
	if stdout.Len() != 0 {
		t.Fatalf("unexpected stdout: %q", stdout.String())
	}
	if stderr.Len() == 0 {
		t.Fatal("expected malformed JSON error on stderr")
	}
}
