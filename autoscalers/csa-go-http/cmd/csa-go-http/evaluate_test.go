package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"testing"
)

type evaluateFixtureIndex struct {
	Cases []string `json:"cases"`
}

type evaluateContractFixture struct {
	Operation string `json:"operation"`
	Input     struct {
		Resource struct {
			Spec struct {
				Replicas int32 `json:"replicas"`
			} `json:"spec"`
		} `json:"resource"`
		Metrics []struct {
			Value string `json:"value"`
		} `json:"metrics"`
	} `json:"input"`
	Config struct {
		EnabledStrategies []string `json:"enabled_strategies"`
		MinReplicas       int32    `json:"minReplicas"`
		MaxReplicas       int32    `json:"maxReplicas"`
		MaxCPU            int64    `json:"maxCPU"`
	} `json:"config"`
	State struct {
		CurrentMCPU int64 `json:"current_mcpu"`
		InitialMCPU int64 `json:"initial_mcpu"`
	} `json:"state"`
	ExpectedResult json.RawMessage `json:"expectedResult"`
}

func TestEvaluateMatchesSharedContractFixtures(t *testing.T) {
	contractsDirectory := filepath.Join("..", "..", "..", "..", "autoscalers", "contracts", "cases")
	indexBytes, err := os.ReadFile(filepath.Join(contractsDirectory, "index.json"))
	if err != nil {
		t.Fatal(err)
	}
	var index evaluateFixtureIndex
	if err := json.Unmarshal(indexBytes, &index); err != nil {
		t.Fatal(err)
	}

	evaluateCases := 0
	for _, caseID := range index.Cases {
		fixtureBytes, err := os.ReadFile(filepath.Join(contractsDirectory, caseID+".json"))
		if err != nil {
			t.Fatal(err)
		}
		var fixture evaluateContractFixture
		if err := json.Unmarshal(fixtureBytes, &fixture); err != nil {
			t.Fatalf("%s: %v", caseID, err)
		}
		if fixture.Operation != "evaluate" {
			continue
		}
		evaluateCases++
		t.Run(caseID, func(t *testing.T) {
			if len(fixture.Input.Metrics) == 0 {
				t.Fatal("fixture has no metric payload")
			}
			var metric struct {
				CurrentValue string `json:"current_value"`
				TargetValue  string `json:"target_value"`
			}
			if err := json.Unmarshal([]byte(fixture.Input.Metrics[0].Value), &metric); err != nil {
				t.Fatal(err)
			}
			currentMetricValue, err := strconv.ParseInt(metric.CurrentValue, 10, 64)
			if err != nil {
				t.Fatalf("invalid current metric fixture value: %v", err)
			}
			targetMetricValue, err := strconv.ParseInt(metric.TargetValue, 10, 64)
			if err != nil {
				t.Fatalf("invalid target metric fixture value: %v", err)
			}

			decision, err := evaluate(evaluationInput{
				currentReplicas:    fixture.Input.Resource.Spec.Replicas,
				currentMetricValue: currentMetricValue,
				targetMetricValue:  targetMetricValue,
				currentMCPU:        fixture.State.CurrentMCPU,
				initialMCPU:        fixture.State.InitialMCPU,
				minReplicas:        fixture.Config.MinReplicas,
				maxReplicas:        fixture.Config.MaxReplicas,
				maxCPU:             fixture.Config.MaxCPU,
				enabledStrategies:  fixture.Config.EnabledStrategies,
			})
			if err != nil {
				t.Fatal(err)
			}
			assertEvaluateJSONEqual(t, fixture.ExpectedResult, decision)
		})
	}
	if evaluateCases == 0 {
		t.Fatal("no evaluate fixtures found")
	}
}

func TestEvaluateRejectsZeroTarget(t *testing.T) {
	_, err := evaluate(evaluationInput{targetMetricValue: 0})
	if err == nil {
		t.Fatal("zero target was accepted")
	}
}

func TestEvaluateClampsDesiredReplicasToConfiguredBounds(t *testing.T) {
	tests := []struct {
		name               string
		currentReplicas    int32
		currentMetricValue int64
		minReplicas        int32
		maxReplicas        int32
		wantReplicas       int32
	}{
		{name: "high load caps at maximum", currentReplicas: 4, currentMetricValue: 4_000_000, minReplicas: 2, maxReplicas: 5, wantReplicas: 5},
		{name: "high load does not apply minimum", currentReplicas: 1, currentMetricValue: 950_000, minReplicas: 3, maxReplicas: 5, wantReplicas: 1},
		{name: "low load applies minimum", currentReplicas: 3, currentMetricValue: 0, minReplicas: 2, maxReplicas: 5, wantReplicas: 2},
		{name: "low load does not cap at maximum", currentReplicas: 10, currentMetricValue: 890_000, minReplicas: 1, maxReplicas: 5, wantReplicas: 9},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			decision, err := evaluate(evaluationInput{
				currentReplicas:    test.currentReplicas,
				currentMetricValue: test.currentMetricValue,
				targetMetricValue:  1000,
				minReplicas:        test.minReplicas,
				maxReplicas:        test.maxReplicas,
				enabledStrategies:  []string{strategyReplicas},
			})
			if err != nil {
				t.Fatal(err)
			}
			if decision == nil {
				t.Fatal("decision = nil, want replica adaptation")
			}
			got := decision.Parameters["replicas"]
			if got != test.wantReplicas {
				t.Errorf("desired replicas = %v, want %d", got, test.wantReplicas)
			}
		})
	}
}

func assertEvaluateJSONEqual(t *testing.T, expectedJSON json.RawMessage, decision *evaluationDecision) {
	t.Helper()
	actualJSON, err := json.Marshal(decision)
	if err != nil {
		t.Fatal(err)
	}
	var expected, actual any
	if err := json.Unmarshal(expectedJSON, &expected); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(actualJSON, &actual); err != nil {
		t.Fatalf("invalid decision JSON %q: %v", actualJSON, err)
	}
	if !reflect.DeepEqual(actual, expected) {
		t.Errorf("decision = %s, want %s", actualJSON, expectedJSON)
	}
}
