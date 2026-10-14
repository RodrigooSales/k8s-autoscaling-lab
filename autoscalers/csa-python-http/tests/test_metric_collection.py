import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import pytest
from kubernetes import client
from kubernetes.config.config_exception import ConfigException

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import main
import metrics


METRIC_PATH = "/apis/external.metrics.k8s.io/v1beta1/namespaces/default/znn_latency_ms_p95"
DEPLOYMENT_PATH = "/apis/apps/v1/namespaces/default/deployments/kube-znn"


@pytest.fixture
def kubernetes_api(monkeypatch):
    previous_api_client = metrics._kubernetes_api_client
    metrics._kubernetes_api_client = None
    state = {
        "requests": [],
        "metric_response": {"items": [{"value": "1200.0001"}, {"value": "300"}]},
    }

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            state["requests"].append(self.path)
            if urlsplit(self.path).path == METRIC_PATH:
                response = state["metric_response"]
            elif urlsplit(self.path).path == DEPLOYMENT_PATH:
                response = {
                    "apiVersion": "apps/v1",
                    "kind": "Deployment",
                    "metadata": {"name": "kube-znn", "namespace": "default"},
                    "spec": {
                        "replicas": 3,
                        "selector": {"matchLabels": {"app": "kube-znn"}},
                        "template": {
                            "metadata": {"labels": {"app": "kube-znn"}},
                            "spec": {"containers": [{"name": "znn", "image": "znn:test"}]},
                        },
                    },
                }
            else:
                self.send_error(404)
                return

            body = json.dumps(response).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    def load_local_config(**kwargs):
        configuration = client.Configuration()
        configuration.host = f"http://127.0.0.1:{server.server_port}"
        configuration.verify_ssl = False
        client.Configuration.set_default(configuration)

    def fail_incluster_config():
        raise ConfigException("test uses an explicit local API server")

    monkeypatch.setattr(metrics.config, "load_incluster_config", fail_incluster_config)
    monkeypatch.setattr(metrics.config, "load_kube_config", load_local_config)
    monkeypatch.delenv("KUBECONFIG", raising=False)
    monkeypatch.delenv("KUBERNETES_SERVICE_HOST", raising=False)
    monkeypatch.delenv("KUBERNETES_SERVICE_PORT", raising=False)
    monkeypatch.setenv("CSA_NAMESPACE", "default")
    monkeypatch.setenv("CSA_DEPLOYMENT_NAME", "kube-znn")
    monkeypatch.setenv("CSA_METRIC_NAME", "znn_latency_ms_p95")
    monkeypatch.delenv("CSA_METRIC_SELECTOR", raising=False)
    monkeypatch.delenv("CSA_TARGET_VALUE", raising=False)

    try:
        yield state
    finally:
        test_api_client = metrics._kubernetes_api_client
        if test_api_client is not None and test_api_client is not previous_api_client:
            test_api_client.close()
        metrics._kubernetes_api_client = previous_api_client
        server.shutdown()
        thread.join()
        server.server_close()


def test_collects_metric_before_deployment_and_returns_milli_snapshot(
    kubernetes_api, monkeypatch
):
    monkeypatch.setenv("CSA_METRIC_SELECTOR", "region=north")

    snapshot = metrics.collect_metrics()

    assert snapshot.current_replicas == 3
    assert snapshot.current_metric_value == 1_500_001
    assert snapshot.target_metric_value == 1000
    assert len(kubernetes_api["requests"]) == 2
    metric_request = urlsplit(kubernetes_api["requests"][0])
    assert metric_request.path == METRIC_PATH
    assert parse_qs(metric_request.query) == {"labelSelector": ["region=north"]}
    assert kubernetes_api["requests"][1] == DEPLOYMENT_PATH


def test_does_not_send_selector_when_unconfigured(kubernetes_api, monkeypatch):
    monkeypatch.delenv("CSA_METRIC_SELECTOR", raising=False)
    monkeypatch.setenv("CSA_TARGET_VALUE", "1001.0")

    snapshot = metrics.collect_metrics()

    assert urlsplit(kubernetes_api["requests"][0]).query == ""
    assert snapshot.target_metric_value == 1001


def test_empty_metric_list_fails_before_reading_deployment(kubernetes_api):
    kubernetes_api["metric_response"] = {"items": []}

    with pytest.raises(metrics.MetricCollectionError, match="no values"):
        metrics.collect_metrics()

    assert len(kubernetes_api["requests"]) == 1
    assert urlsplit(kubernetes_api["requests"][0]).path == METRIC_PATH


def test_reuses_lazily_created_kubernetes_client_for_multiple_collections(
    kubernetes_api, monkeypatch
):
    client_loads = 0
    client_creations = 0
    load_local_config = metrics.config.load_kube_config
    api_client_constructor = client.ApiClient

    def count_config_loads(**kwargs):
        nonlocal client_loads
        client_loads += 1
        load_local_config(**kwargs)

    def count_api_client_creations(*args, **kwargs):
        nonlocal client_creations
        client_creations += 1
        return api_client_constructor(*args, **kwargs)

    monkeypatch.setattr(metrics.config, "load_kube_config", count_config_loads)
    monkeypatch.setattr(metrics.client, "ApiClient", count_api_client_creations)

    metrics.collect_metrics()
    metrics.collect_metrics()

    assert client_loads == 1
    assert client_creations == 1
    assert [urlsplit(path).path for path in kubernetes_api["requests"]] == [
        METRIC_PATH,
        DEPLOYMENT_PATH,
        METRIC_PATH,
        DEPLOYMENT_PATH,
    ]


@pytest.mark.parametrize(
    "setting",
    ["CSA_NAMESPACE", "CSA_DEPLOYMENT_NAME", "CSA_METRIC_NAME"],
)
@pytest.mark.parametrize("value", [None, ""])
def test_required_settings_reject_missing_or_empty_values(
    kubernetes_api, monkeypatch, setting, value
):
    if value is None:
        monkeypatch.delenv(setting, raising=False)
    else:
        monkeypatch.setenv(setting, value)

    with pytest.raises(metrics.MetricCollectionError, match=setting):
        metrics.collect_metrics()

    assert kubernetes_api["requests"] == []
    assert metrics._kubernetes_api_client is None


def test_health_does_not_require_kubernetes_configuration(monkeypatch):
    monkeypatch.delenv("KUBERNETES_SERVICE_HOST", raising=False)
    monkeypatch.delenv("KUBERNETES_SERVICE_PORT", raising=False)

    assert metrics._kubernetes_api_client is None
    assert main.health() == {"status": "UP"}
    assert metrics._kubernetes_api_client is None
