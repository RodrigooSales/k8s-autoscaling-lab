import json
import os
from dataclasses import dataclass
from decimal import InvalidOperation
from threading import Lock
from typing import Any

from kubernetes import client, config
from kubernetes.config.config_exception import ConfigException
from kubernetes.utils.quantity import parse_quantity

_kubernetes_api_client: client.ApiClient | None = None
_kubernetes_api_client_lock = Lock()


@dataclass(frozen=True)
class MetricSnapshot:
    current_replicas: int
    current_metric_value: int
    target_metric_value: int
    raw_current_metric_value: str | None = None
    raw_target_metric_value: str | None = None


class MetricCollectionError(RuntimeError):
    pass


def collect_metrics(spec: dict[str, Any]) -> MetricSnapshot:
    resource = _object_field(spec, "resource")
    resource_spec = _object_field(resource, "spec")
    replicas = resource_spec.get("replicas")
    if not isinstance(replicas, int) or isinstance(replicas, bool):
        raise MetricCollectionError("resource.spec.replicas must be an integer")

    kubernetes_metrics = spec.get("kubernetesMetrics")
    if not isinstance(kubernetes_metrics, list) or not kubernetes_metrics:
        raise MetricCollectionError("kubernetesMetrics must contain at least one metric")
    metric = _object(kubernetes_metrics[0], "kubernetesMetrics[0]")
    target_value = _nested_metric_value(
        metric, ("spec", "external", "target", "value"), "target value"
    )
    current_value = _nested_metric_value(
        metric, ("external", "current", "value"), "current value"
    )

    return MetricSnapshot(
        current_replicas=replicas,
        current_metric_value=_quantity_integer(current_value, "current metric value"),
        target_metric_value=_quantity_integer(target_value, "target metric value"),
        raw_current_metric_value=current_value,
        raw_target_metric_value=target_value,
    )


def snapshot_from_evaluation(spec: dict[str, Any]) -> MetricSnapshot:
    resource = _object_field(spec, "resource")
    resource_spec = _object_field(resource, "spec")
    replicas = resource_spec.get("replicas")
    if not isinstance(replicas, int) or isinstance(replicas, bool):
        raise MetricCollectionError("resource.spec.replicas must be an integer")

    metric_list = spec.get("metrics")
    if not isinstance(metric_list, list) or not metric_list:
        raise MetricCollectionError("metrics must contain at least one metric")
    metric = _object(metric_list[0], "metrics[0]")
    metric_value = metric.get("value")
    if not isinstance(metric_value, str):
        raise MetricCollectionError("metrics[0].value must contain JSON text")
    try:
        payload = json.loads(metric_value)
    except json.JSONDecodeError as error:
        raise MetricCollectionError("metrics[0].value contains invalid JSON") from error
    if not isinstance(payload, dict):
        raise MetricCollectionError("metrics[0].value must decode to an object")

    current_value = _value_as_text(payload.get("current_value"), "current metric value")
    target_value = _value_as_text(payload.get("target_value"), "target metric value")
    return MetricSnapshot(
        current_replicas=replicas,
        current_metric_value=_quantity_integer(current_value, "current metric value"),
        target_metric_value=_quantity_integer(target_value, "target metric value"),
        raw_current_metric_value=current_value,
        raw_target_metric_value=target_value,
    )


def metric_result(snapshot: MetricSnapshot) -> dict[str, Any]:
    return {
        "current_replicas": snapshot.current_replicas,
        "target_value": snapshot.raw_target_metric_value or str(snapshot.target_metric_value),
        "current_value": snapshot.raw_current_metric_value or str(snapshot.current_metric_value),
    }


def _object_field(value: Any, name: str) -> dict[str, Any]:
    child = value.get(name) if isinstance(value, dict) else None
    return _object(child, name)


def _object(value: Any, name: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise MetricCollectionError(f"{name} must be an object")
    return value


def _nested_metric_value(
    metric: dict[str, Any], path: tuple[str, ...], label: str
) -> str:
    value: Any = metric
    for field in path:
        value = value.get(field) if isinstance(value, dict) else None
    return _value_as_text(value, label)


def _value_as_text(value: Any, label: str) -> str:
    if isinstance(value, bool) or value is None or isinstance(value, (dict, list)):
        raise MetricCollectionError(f"{label} is missing or invalid")
    return str(value)


def _quantity_integer(value: str, label: str) -> int:
    try:
        parsed = parse_quantity(value)
        return int(parsed)
    except (ArithmeticError, InvalidOperation, TypeError, ValueError) as error:
        raise MetricCollectionError(f"Invalid {label}: {value}") from error


def _get_kubernetes_api_client() -> client.ApiClient:
    global _kubernetes_api_client
    if _kubernetes_api_client is None:
        with _kubernetes_api_client_lock:
            if _kubernetes_api_client is None:
                try:
                    config.load_incluster_config()
                except ConfigException:
                    try:
                        config.load_kube_config(config_file=os.getenv("KUBECONFIG"))
                    except ConfigException as error:
                        raise MetricCollectionError(
                            "Failed to load Kubernetes configuration"
                        ) from error
                _kubernetes_api_client = client.ApiClient()
    return _kubernetes_api_client


def _required_setting(name: str) -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        raise MetricCollectionError(f"Required configuration {name} must not be empty")
    return value
