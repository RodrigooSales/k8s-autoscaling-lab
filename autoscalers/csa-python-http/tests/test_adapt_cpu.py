import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import adapt_cpu
import adapt_state


def test_cpu_adapter_resizes_both_workload_containers_with_configured_bounds(monkeypatch):
    pod = SimpleNamespace(metadata=SimpleNamespace(name="kube-znn-abc"))
    calls = []

    class CoreApi:
        def patch_namespaced_pod_resize(self, **request):
            calls.append(request)

    context = SimpleNamespace(
        namespace="default",
        deployment_name="kube-znn",
        core=CoreApi(),
        rollout_in_progress=lambda: False,
        initial_mcpu=lambda: 500,
        running_pods=lambda: [pod],
        current_mcpu=lambda _pods: 600,
    )
    monkeypatch.setattr(adapt_cpu, "AdaptationContext", lambda: context)

    result = adapt_cpu.adapt_cpu(1.3, 750)

    assert result == {"cpu": 750}
    assert calls == [
        {
            "name": "kube-znn-abc",
            "namespace": "default",
            "body": {
                "spec": {
                    "containers": [
                        {"name": "znn", "resources": {"limits": {"cpu": "750m"}}},
                        {"name": "nginx", "resources": {"limits": {"cpu": "750m"}}},
                    ]
                }
            },
        }
    ]


def test_cpu_adapter_does_not_resize_during_a_rollout(monkeypatch):
    context = SimpleNamespace(
        rollout_in_progress=lambda: True,
        core=SimpleNamespace(
            patch_namespaced_pod_resize=lambda **_request: pytest.fail("must not resize")
        ),
    )
    monkeypatch.setattr(adapt_cpu, "AdaptationContext", lambda: context)

    assert adapt_cpu.adapt_cpu(1.1) == {"result": "skip"}


def test_cpu_adapter_rejects_boolean_multiplier():
    with pytest.raises(adapt_state.AdaptationError, match="cpu_multiplier must be a number"):
        adapt_cpu.adapt_cpu(True)


def test_initial_data_patch_preserves_values_already_in_operator_status():
    requests = []

    class CustomApi:
        def get_namespaced_custom_object_status(self, **_request):
            return {"status": {"initialData": '{"tag":"200k"}'}}

    context = adapt_state.AdaptationContext.__new__(adapt_state.AdaptationContext)
    context.namespace = "default"
    context.csa_name = "csa-znn-python-http"
    context.custom = CustomApi()
    context.core = SimpleNamespace(patch_namespaced_pod=lambda **request: requests.append(request))

    context.store_data("cpu_limit", 500)

    assert requests == [
        {
            "name": "csa-znn-python-http",
            "namespace": "default",
            "body": {
                "metadata": {
                    "annotations": {
                        adapt_state.INITIAL_DATA_ANNOTATION: '{"tag": "200k", "cpu_limit": 500}'
                    }
                }
            },
        }
    ]
