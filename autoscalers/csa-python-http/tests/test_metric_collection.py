import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import main
from metrics import MetricCollectionError, collect_metrics


def test_collects_first_runtime_metric_and_preserves_callback_values():
    snapshot = collect_metrics(
        {
            "resource": {"spec": {"replicas": 2}},
            "kubernetesMetrics": [
                {
                    "spec": {"external": {"target": {"value": "1000"}}},
                    "external": {"current": {"value": "950000"}},
                },
                {
                    "spec": {"external": {"target": {"value": "2000"}}},
                    "external": {"current": {"value": "999999"}},
                },
            ],
        }
    )

    assert snapshot.current_replicas == 2
    assert snapshot.current_metric_value == 950000
    assert snapshot.target_metric_value == 1000
    assert main.metrics.metric_result(snapshot) == {
        "current_replicas": 2,
        "target_value": "1000",
        "current_value": "950000",
    }


@pytest.mark.parametrize(
    ("spec", "message"),
    [
        ({"resource": {"spec": {"replicas": 1}}, "kubernetesMetrics": []}, "at least one"),
        ({"resource": {"spec": {"replicas": 1}}}, "at least one"),
        ({"resource": {"spec": {"replicas": "1"}}, "kubernetesMetrics": []}, "replicas"),
    ],
)
def test_rejects_invalid_runtime_metric_payload(spec, message):
    with pytest.raises(MetricCollectionError, match=message):
        collect_metrics(spec)


def test_health_does_not_load_a_profile_or_kubernetes_client(monkeypatch):
    monkeypatch.setattr(main, "load_profile", lambda: pytest.fail("health loaded profile"))

    assert main.health() == {"status": "UP"}
