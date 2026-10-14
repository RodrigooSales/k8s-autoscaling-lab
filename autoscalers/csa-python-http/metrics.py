import os
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_CEILING
from threading import Lock

from kubernetes import client, config
from kubernetes.client.rest import ApiException
from kubernetes.config.config_exception import ConfigException
from kubernetes.utils.quantity import parse_quantity

_kubernetes_api_client: client.ApiClient | None = None
_kubernetes_api_client_lock = Lock()


@dataclass(frozen=True)
class MetricSnapshot:
    current_replicas: int
    current_metric_value: int
    target_metric_value: int


class MetricCollectionError(RuntimeError):
    pass


def collect_metrics() -> MetricSnapshot:
    namespace = _required_setting("CSA_NAMESPACE")
    deployment_name = _required_setting("CSA_DEPLOYMENT_NAME")
    metric_name = _required_setting("CSA_METRIC_NAME")
    metric_selector = os.getenv("CSA_METRIC_SELECTOR", "")
    target_metric_value = _target_metric_value(os.getenv("CSA_TARGET_VALUE", "1000"))

    api_client = _get_kubernetes_api_client()
    external_metrics = client.CustomObjectsApi(api_client)
    deployments = client.AppsV1Api(api_client)
    metric_request = {
        "group": "external.metrics.k8s.io",
        "version": "v1beta1",
        "namespace": namespace,
        "plural": metric_name,
    }
    if metric_selector.strip():
        metric_request["label_selector"] = metric_selector

    try:
        metric_list = external_metrics.list_namespaced_custom_object(**metric_request)
    except ApiException as error:
        raise MetricCollectionError(
            f"Failed to read external metric {namespace}/{metric_name}"
        ) from error

    items = metric_list.get("items") if isinstance(metric_list, dict) else None
    if not isinstance(items, list):
        raise MetricCollectionError("External metric response has no items list")
    if not items:
        raise MetricCollectionError(f"External metric {namespace}/{metric_name} has no values")
    current_metric_value = 0
    for item in items:
        value = item.get("value") if isinstance(item, dict) else None
        if not isinstance(value, str):
            raise MetricCollectionError("External metric response item has no value")
        try:
            current_metric_value += int(
                (parse_quantity(value) * 1000).to_integral_value(rounding=ROUND_CEILING)
            )
        except (ArithmeticError, InvalidOperation, TypeError, ValueError) as error:
            raise MetricCollectionError(f"Invalid external metric value: {value}") from error

    try:
        deployment = deployments.read_namespaced_deployment(
            name=deployment_name,
            namespace=namespace,
        )
    except ApiException as error:
        raise MetricCollectionError(
            f"Failed to read Deployment {namespace}/{deployment_name}"
        ) from error

    replicas = (
        deployment.spec.replicas
        if deployment is not None and deployment.spec is not None
        else None
    )
    if replicas is None:
        raise MetricCollectionError(
            f"Deployment {namespace}/{deployment_name} has no configured replica count"
        )

    return MetricSnapshot(replicas, current_metric_value, target_metric_value)


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


def _target_metric_value(value: str) -> int:
    try:
        target = Decimal(value)
    except InvalidOperation as error:
        raise MetricCollectionError(f"Invalid configured target metric: {value}") from error

    if not target.is_finite() or target != target.to_integral_value():
        raise MetricCollectionError("Configured target metric must be an integer")
    return int(target)


def _required_setting(name: str) -> str:
    value = os.getenv(name)
    if value is None or not value.strip():
        raise MetricCollectionError(f"Required configuration {name} must not be empty")
    return value
