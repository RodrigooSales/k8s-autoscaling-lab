import sys
from pathlib import Path

import pytest
from kubernetes.client.rest import ApiException

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import adapt_replicas


def test_patches_only_replica_count_with_shared_kubernetes_client(monkeypatch):
    api_client = object()
    calls = []

    class AppsApi:
        def patch_namespaced_deployment(self, **request):
            calls.append(request)

    monkeypatch.setenv("CSA_NAMESPACE", "default")
    monkeypatch.setenv("CSA_DEPLOYMENT_NAME", "kube-znn")
    monkeypatch.setattr(adapt_replicas.metrics, "_get_kubernetes_api_client", lambda: api_client)
    monkeypatch.setattr(
        adapt_replicas.client,
        "AppsV1Api",
        lambda actual_client: AppsApi() if actual_client is api_client else None,
    )

    result = adapt_replicas.adapt_replicas(4)

    assert result == {"replicas": 4}
    assert calls == [
        {
            "name": "kube-znn",
            "namespace": "default",
            "body": {"spec": {"replicas": 4}},
        }
    ]


def test_rejects_non_integer_replica_count_before_creating_client(monkeypatch):
    monkeypatch.setenv("CSA_NAMESPACE", "default")
    monkeypatch.setenv("CSA_DEPLOYMENT_NAME", "kube-znn")
    monkeypatch.setattr(
        adapt_replicas.metrics,
        "_get_kubernetes_api_client",
        lambda: pytest.fail("invalid replicas must fail before client initialization"),
    )

    with pytest.raises(adapt_replicas.AdaptationError, match="replicas must be an integer"):
        adapt_replicas.adapt_replicas(2.5)


def test_wraps_kubernetes_patch_failure(monkeypatch):
    class AppsApi:
        def patch_namespaced_deployment(self, **request):
            raise ApiException(status=500, reason="server error")

    monkeypatch.setenv("CSA_NAMESPACE", "default")
    monkeypatch.setenv("CSA_DEPLOYMENT_NAME", "kube-znn")
    monkeypatch.setattr(adapt_replicas.metrics, "_get_kubernetes_api_client", object)
    monkeypatch.setattr(adapt_replicas.client, "AppsV1Api", lambda _client: AppsApi())

    with pytest.raises(adapt_replicas.AdaptationError, match="Failed to patch Deployment"):
        adapt_replicas.adapt_replicas(4)
