package csa

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type cpuQuantityContract struct {
	Operation      string          `json:"operation"`
	Input          json.RawMessage `json:"input"`
	ExpectedResult json.RawMessage `json:"expectedResult"`
}

func TestCPUQuantityContractFixtures(t *testing.T) {
	contractsDir := filepath.Join("..", "..", "..", "contracts", "cases")
	indexBytes, err := os.ReadFile(filepath.Join(contractsDir, "index.json"))
	if err != nil {
		t.Fatal(err)
	}
	var index contractIndex
	if err := json.Unmarshal(indexBytes, &index); err != nil {
		t.Fatal(err)
	}

	cpuCases := 0
	for _, caseID := range index.Cases {
		fixtureBytes, err := os.ReadFile(filepath.Join(contractsDir, caseID+".json"))
		if err != nil {
			t.Fatal(err)
		}
		var fixture cpuQuantityContract
		if err := json.Unmarshal(fixtureBytes, &fixture); err != nil {
			t.Fatalf("%s: %v", caseID, err)
		}
		if fixture.Operation != "cpu_quantity" {
			continue
		}
		cpuCases++
		t.Run(caseID, func(t *testing.T) {
			var input struct {
				CPU string `json:"cpu"`
			}
			if err := json.Unmarshal(fixture.Input, &input); err != nil {
				t.Fatal(err)
			}
			got, ok := parseCPUToMilli(input.CPU)
			if string(fixture.ExpectedResult) == "null" {
				if ok {
					t.Fatalf("parseCPUToMilli(%q) = %d, want invalid", input.CPU, got)
				}
				return
			}
			var want int64
			if err := json.Unmarshal(fixture.ExpectedResult, &want); err != nil {
				t.Fatal(err)
			}
			if !ok || got != want {
				t.Fatalf("parseCPUToMilli(%q) = %d, %t; want %d, true", input.CPU, got, ok, want)
			}
		})
	}
	if cpuCases == 0 {
		t.Fatal("no cpu_quantity fixtures found")
	}
}
