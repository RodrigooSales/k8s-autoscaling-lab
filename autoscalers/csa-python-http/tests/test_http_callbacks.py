import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import adapt_replicas
import main
from metrics import MetricSnapshot


def test_metric_callback_uses_runtime_metric_payload(monkeypatch):
    monkeypatch.setattr(
        main.metrics,
        "collect_metrics",
        lambda _spec: MetricSnapshot(
            current_replicas=2,
            current_metric_value=950000,
            target_metric_value=1000,
            raw_current_metric_value="950000",
            raw_target_metric_value="1000",
        ),
    )

    result = _endpoint("/metric")({"resource": {}, "kubernetesMetrics": []})

    assert result == {
        "current_replicas": 2,
        "target_value": "1000",
        "current_value": "950000",
    }


def test_evaluate_callback_returns_runtime_evaluation_contract(monkeypatch):
    monkeypatch.setattr(
        main,
        "load_profile",
        lambda: {
            "enabled_strategies": ["adapt_replicas"],
            "minReplicas": 1,
            "maxReplicas": 5,
            "maxCPU": 750,
        },
    )
    spec = {
        "resource": {"spec": {"replicas": 2}},
        "metrics": [
            {"value": json.dumps({"current_value": "950000", "target_value": "1000"})}
        ],
    }

    result = _endpoint("/evaluate")(spec)

    assert result == {
        "strategy": "adapt_replicas",
        "parameters": {"replicas": 2},
    }


def test_evaluate_returns_empty_object_when_no_adaptation_is_selected(monkeypatch):
    monkeypatch.setattr(
        main,
        "load_profile",
        lambda: {"enabled_strategies": ["adapt_replicas"], "minReplicas": 1, "maxReplicas": 5},
    )
    spec = {
        "resource": {"spec": {"replicas": 2}},
        "metrics": [
            {"value": json.dumps({"current_value": "940000", "target_value": "1000"})}
        ],
    }

    result = _endpoint("/evaluate")(spec)

    assert result == {}


def test_replica_callback_applies_the_evaluated_replica_count(monkeypatch):
    calls = []

    def apply_replicas(replicas):
        calls.append(replicas)
        return {"replicas": replicas}

    monkeypatch.setattr(adapt_replicas, "adapt_replicas", apply_replicas)
    spec = {"evaluation": {"parameters": {"replicas": 4}}}

    result = _endpoint("/adapt/replicas")(spec)

    assert result == {"replicas": 4}
    assert calls == [4]


def _endpoint(path):
    for route in main.app.routes:
        if getattr(route, "path", None) == path:
            return route.endpoint
    raise AssertionError(f"HTTP callback route {path} is missing")
