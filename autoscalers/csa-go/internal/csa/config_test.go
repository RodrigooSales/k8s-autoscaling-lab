package csa

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadConfigParsesYAMLValues(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.yaml")
	configYAML := "interval: 5000\nminReplicas: 1\nmaxReplicas: 5\nenabled_strategies:\n  - adapt_cpu\n  - adapt_tag\n"
	if err := os.WriteFile(configPath, []byte(configYAML), 0o600); err != nil {
		t.Fatal(err)
	}

	var stderr strings.Builder
	config, err := loadConfig(configPath, &stderr)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := config["interval"], 5000; got != want {
		t.Errorf("interval = %#v, want %d", got, want)
	}
	if got, want := config["minReplicas"], 1; got != want {
		t.Errorf("minReplicas = %#v, want %d", got, want)
	}
	if got, want := config["maxReplicas"], 5; got != want {
		t.Errorf("maxReplicas = %#v, want %d", got, want)
	}
	strategies, ok := config["enabled_strategies"].([]any)
	if !ok || len(strategies) != 2 || strategies[0] != "adapt_cpu" || strategies[1] != "adapt_tag" {
		t.Errorf("enabled_strategies = %#v, want [adapt_cpu adapt_tag]", config["enabled_strategies"])
	}
	if stderr.Len() != 0 {
		t.Errorf("unexpected stderr: %q", stderr.String())
	}
}

func TestLoadEmptyOrNullConfigReturnsNilAndDiagnostic(t *testing.T) {
	for _, content := range []string{"", "null\n"} {
		t.Run(strings.TrimSpace(content), func(t *testing.T) {
			configPath := filepath.Join(t.TempDir(), "config.yaml")
			if err := os.WriteFile(configPath, []byte(content), 0o600); err != nil {
				t.Fatal(err)
			}

			var stderr strings.Builder
			config, err := loadConfig(configPath, &stderr)
			if err != nil {
				t.Fatal(err)
			}
			if config != nil {
				t.Fatalf("config = %#v, want nil", config)
			}
			if got, want := stderr.String(), "config file was empty"; got != want {
				t.Fatalf("stderr = %q, want %q", got, want)
			}
		})
	}
}

func TestLoadMalformedConfigReturnsNilAndDiagnostic(t *testing.T) {
	configPath := filepath.Join(t.TempDir(), "config.yaml")
	if err := os.WriteFile(configPath, []byte("invalid: [yaml"), 0o600); err != nil {
		t.Fatal(err)
	}

	var stderr strings.Builder
	config, err := loadConfig(configPath, &stderr)
	if err != nil {
		t.Fatal(err)
	}
	if config != nil {
		t.Fatalf("config = %#v, want nil", config)
	}
	if !strings.HasPrefix(stderr.String(), "failed to parse config file:") {
		t.Fatalf("stderr = %q, want YAML parse diagnostic", stderr.String())
	}
}

func TestLoadConfigPropagatesFileErrors(t *testing.T) {
	config, err := loadConfig(filepath.Join(t.TempDir(), "missing.yaml"), &strings.Builder{})
	if err == nil {
		t.Fatal("loadConfig returned no error for a missing file")
	}
	if config != nil {
		t.Fatalf("config = %#v, want nil", config)
	}
}
