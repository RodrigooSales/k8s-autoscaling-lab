package csa

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

type contractIndex struct {
	Cases []string `json:"cases"`
}

type metricContract struct {
	Operation      string          `json:"operation"`
	Input          json.RawMessage `json:"input"`
	ExpectedResult json.RawMessage `json:"expectedResult"`
}

func TestMetricContractFixtures(t *testing.T) {
	contractsDir := filepath.Join("..", "..", "..", "contracts", "cases")
	indexBytes, err := os.ReadFile(filepath.Join(contractsDir, "index.json"))
	if err != nil {
		t.Fatal(err)
	}
	var index contractIndex
	if err := json.Unmarshal(indexBytes, &index); err != nil {
		t.Fatal(err)
	}

	metricCases := 0
	for _, caseID := range index.Cases {
		fixtureBytes, err := os.ReadFile(filepath.Join(contractsDir, caseID+".json"))
		if err != nil {
			t.Fatal(err)
		}
		var fixture metricContract
		if err := json.Unmarshal(fixtureBytes, &fixture); err != nil {
			t.Fatalf("%s: %v", caseID, err)
		}
		if fixture.Operation != "metric" {
			continue
		}
		metricCases++
		t.Run(caseID, func(t *testing.T) {
			var stderr strings.Builder
			logPath := filepath.Join(t.TempDir(), "adapter.log")
			logger, err := newAdapterLogger("metric", logPath, &stderr)
			if err != nil {
				t.Fatal(err)
			}
			output, resultErr := metricResult(fixture.Input, logger)
			if err := logger.close(); err != nil {
				t.Fatal(err)
			}
			if resultErr != nil {
				t.Fatal(resultErr)
			}
			assertJSONEqual(t, fixture.ExpectedResult, output)
			assertContains(t, stderr.String(), "Starting metric script")
			logBytes, err := os.ReadFile(logPath)
			if err != nil {
				t.Fatal(err)
			}
			assertContains(t, string(logBytes), "Starting metric script")
		})
	}
	if metricCases == 0 {
		t.Fatal("no metric fixtures found")
	}
}

func assertJSONEqual(t *testing.T, wantRaw, gotRaw []byte) {
	t.Helper()
	var want, got any
	if err := json.Unmarshal(wantRaw, &want); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(gotRaw, &got); err != nil {
		t.Fatalf("invalid JSON output %q: %v", gotRaw, err)
	}
	if !reflect.DeepEqual(want, got) {
		t.Fatalf("JSON result = %#v, want %#v", got, want)
	}
}

func assertContains(t *testing.T, value, fragment string) {
	t.Helper()
	if !strings.Contains(value, fragment) {
		t.Fatalf("%q does not contain %q", value, fragment)
	}
}
