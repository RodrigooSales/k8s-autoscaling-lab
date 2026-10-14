import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from evaluate import EvaluationError, evaluate
from metrics import MetricSnapshot


CONTRACT_CASES = (
    Path(__file__).resolve().parents[3] / "autoscalers/contracts/cases"
)


def test_evaluation_contract_fixtures():
    index = json.loads((CONTRACT_CASES / "index.json").read_text())
    evaluate_cases = 0

    for case_name in index["cases"]:
        if not case_name.startswith("evaluate_"):
            continue

        case = json.loads((CONTRACT_CASES / f"{case_name}.json").read_text())
        metric = json.loads(case["input"]["metrics"][0]["value"])
        state = case["state"]
        snapshot = MetricSnapshot(
            current_replicas=case["input"]["resource"]["spec"]["replicas"],
            current_metric_value=int(metric["current_value"]),
            target_metric_value=int(metric["target_value"]),
        )

        result = evaluate(
            snapshot,
            case["config"],
            state["current_mcpu"],
            state["initial_mcpu"],
        )

        assert result == case["expectedResult"], case_name
        evaluate_cases += 1

    assert evaluate_cases > 0, "no evaluate contract fixtures found"


def test_zero_target_metric_raises_specific_error():
    snapshot = MetricSnapshot(
        current_replicas=1,
        current_metric_value=950_000,
        target_metric_value=0,
    )

    with pytest.raises(EvaluationError, match="target metric is zero"):
        evaluate(snapshot, {"enabled_strategies": ["adapt_replicas"]}, 500, 500)


def test_high_load_tag_fallback_reports_cpu_strategy_state():
    snapshot = MetricSnapshot(
        current_replicas=5,
        current_metric_value=980_000,
        target_metric_value=1_000,
    )

    result = evaluate(
        snapshot,
        {"enabled_strategies": ["adapt_tag"], "maxReplicas": 5},
        750,
        500,
    )

    assert result == {
        "strategy": "adapt_tag",
        "parameters": {"tag_up": False, "update_cpu": False},
    }


@pytest.mark.parametrize(
    ("replicas", "current_metric_value", "config", "expected_replicas"),
    [
        (4, 1_500_000, {"enabled_strategies": ["adapt_replicas"], "maxReplicas": 5}, 5),
        (3, 50_000, {"enabled_strategies": ["adapt_replicas"], "minReplicas": 2}, 2),
    ],
)
def test_replica_decision_respects_configured_bounds(
    replicas, current_metric_value, config, expected_replicas
):
    snapshot = MetricSnapshot(
        current_replicas=replicas,
        current_metric_value=current_metric_value,
        target_metric_value=1_000,
    )

    result = evaluate(snapshot, config, 500, 500)

    assert result == {
        "strategy": "adapt_replicas",
        "parameters": {"replicas": expected_replicas},
    }
