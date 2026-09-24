package csa

import (
	"encoding/json"
	"math/big"
	"os"
	"path/filepath"
	"testing"
)

type evaluateContract struct {
	Operation      string          `json:"operation"`
	Input          json.RawMessage `json:"input"`
	Config         json.RawMessage `json:"config"`
	State          json.RawMessage `json:"state"`
	ExpectedResult json.RawMessage `json:"expectedResult"`
}

func TestEvaluateContractFixtures(t *testing.T) {
	contractsDir := filepath.Join("..", "..", "..", "contracts", "cases")
	indexBytes, err := os.ReadFile(filepath.Join(contractsDir, "index.json"))
	if err != nil {
		t.Fatal(err)
	}
	var index contractIndex
	if err := json.Unmarshal(indexBytes, &index); err != nil {
		t.Fatal(err)
	}

	evaluateCases := 0
	for _, caseID := range index.Cases {
		fixtureBytes, err := os.ReadFile(filepath.Join(contractsDir, caseID+".json"))
		if err != nil {
			t.Fatal(err)
		}
		var fixture evaluateContract
		if err := json.Unmarshal(fixtureBytes, &fixture); err != nil {
			t.Fatalf("%s: %v", caseID, err)
		}
		if fixture.Operation != "evaluate" {
			continue
		}
		evaluateCases++
		t.Run(caseID, func(t *testing.T) {
			var input, config map[string]any
			if err := json.Unmarshal(fixture.Input, &input); err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal(fixture.Config, &config); err != nil {
				t.Fatal(err)
			}
			var state map[string]any
			if err := json.Unmarshal(fixture.State, &state); err != nil {
				t.Fatal(err)
			}
			currentMCPU, err := numberAsInt64(state["current_mcpu"])
			if err != nil {
				t.Fatal(err)
			}
			initialMCPU, err := numberAsInt64(state["initial_mcpu"])
			if err != nil {
				t.Fatal(err)
			}

			result, err := evaluateDecision(input, config, currentMCPU, initialMCPU)
			if err != nil {
				t.Fatal(err)
			}
			actual, err := json.Marshal(result)
			if err != nil {
				t.Fatal(err)
			}
			assertJSONEqual(t, fixture.ExpectedResult, actual)
		})
	}
	if evaluateCases == 0 {
		t.Fatal("no evaluate fixtures found")
	}
}

func TestEvaluateMetricQuantitiesTruncateTowardZero(t *testing.T) {
	for raw, want := range map[string]int64{
		"1000":   1000,
		"10M":    10_000_000,
		"10000m": 10,
		"0.9":    0,
		"-1.9":   -1,
	} {
		t.Run(raw, func(t *testing.T) {
			got, err := parseQuantityInteger(raw)
			if err != nil {
				t.Fatal(err)
			}
			if got.Cmp(big.NewInt(want)) != 0 {
				t.Fatalf("parseQuantityInteger(%q) = %d, want %d", raw, got, want)
			}
		})
	}
}
