import json
import math
import os
from typing import Any

from kubernetes import client
from kubernetes.client.rest import ApiException
from kubernetes.utils.quantity import parse_quantity

import metrics

INITIAL_DATA_ANNOTATION = "csa.custom-self-adapter.net/initialData"
INITIAL_DATA_STATUS_KEY = "initialData"
CUSTOM_ADAPTER_GROUP = "custom-self-adapter.net"
CUSTOM_ADAPTER_VERSION = "v1"
CUSTOM_ADAPTER_PLURAL = "customselfadapters"


class AdaptationError(RuntimeError):
    pass


class AdaptationContext:
    def __init__(self) -> None:
        try:
            self.namespace = metrics._required_setting("CSA_NAMESPACE")
            self.csa_name = metrics._required_setting("CSA_NAME")
            self.deployment_name = metrics._required_setting("CSA_DEPLOYMENT_NAME")
            api_client = metrics._get_kubernetes_api_client()
        except metrics.MetricCollectionError as error:
            raise AdaptationError("Failed to configure Kubernetes client") from error

        self.core = client.CoreV1Api(api_client)
        self.apps = client.AppsV1Api(api_client)
        self.custom = client.CustomObjectsApi(api_client)
        try:
            self.deployment = self.apps.read_namespaced_deployment(
                name=self.deployment_name, namespace=self.namespace
            )
        except ApiException as error:
            raise AdaptationError(
                f"Failed to read Deployment {self.namespace}/{self.deployment_name}"
            ) from error

    def rollout_in_progress(self) -> bool:
        status = self.deployment.status
        spec = self.deployment.spec
        observed = status.observed_generation or 0
        desired_generation = self.deployment.metadata.generation or 0
        replicas = spec.replicas or 0
        updated = status.updated_replicas or 0
        available = status.available_replicas or 0
        return not (
            observed >= desired_generation
            and updated == replicas
            and available == replicas
        )

    def running_pods(self) -> list[Any]:
        selector = self.deployment.spec.selector.match_labels or {}
        label_selector = ",".join(f"{key}={value}" for key, value in selector.items())
        try:
            return self.core.list_namespaced_pod(
                namespace=self.namespace, label_selector=label_selector or None
            ).items
        except ApiException as error:
            raise AdaptationError(
                f"Failed to list Pods for Deployment {self.namespace}/{self.deployment_name}"
            ) from error

    def current_mcpu(self, pods: list[Any] | None = None) -> int:
        current_values = []
        for pod in pods if pods is not None else self.running_pods():
            containers = pod.spec.containers or []
            if not containers or containers[0].resources is None:
                continue
            limits = containers[0].resources.limits or {}
            cpu = limits.get("cpu")
            if cpu:
                parsed = parse_cpu_mcpu(cpu)
                if parsed is not None:
                    current_values.append(parsed)
        if not current_values:
            raise AdaptationError("Current CPU limit not found or unparsable")
        return max(current_values)

    def spec_mcpu(self) -> int:
        for container in self.deployment.spec.template.spec.containers:
            if container.resources:
                cpu = (container.resources.limits or {}).get("cpu")
                if cpu:
                    value = parse_cpu_mcpu(cpu)
                    if value is not None:
                        return value
        return 0

    def stored_data(self, field: str) -> Any:
        status = self._csa_status()
        serialized = status.get(INITIAL_DATA_STATUS_KEY) if status else None
        if not serialized:
            return None
        try:
            initial_data = json.loads(serialized)
        except (TypeError, json.JSONDecodeError) as error:
            raise AdaptationError("Invalid status.initialData JSON") from error
        if not isinstance(initial_data, dict):
            raise AdaptationError("status.initialData must contain a JSON object")
        return initial_data.get(field)

    def store_data(self, field: str, value: Any) -> None:
        stored = self.stored_data(field)
        if stored is not None and stored != "":
            return
        status = self._csa_status()
        initial_data: dict[str, Any] = {}
        if status and status.get(INITIAL_DATA_STATUS_KEY):
            try:
                initial_data = json.loads(status[INITIAL_DATA_STATUS_KEY])
            except (TypeError, json.JSONDecodeError) as error:
                raise AdaptationError("Invalid status.initialData JSON") from error
            if not isinstance(initial_data, dict):
                raise AdaptationError("status.initialData must contain a JSON object")
        initial_data[field] = value

        try:
            self.core.patch_namespaced_pod(
                name=self.csa_name,
                namespace=self.namespace,
                body={
                    "metadata": {
                        "annotations": {
                            INITIAL_DATA_ANNOTATION: json.dumps(initial_data)
                        }
                    }
                },
            )
        except ApiException as error:
            raise AdaptationError(
                f"Failed to store initialData on CSA Pod {self.namespace}/{self.csa_name}"
            ) from error

    def initial_mcpu(self) -> int:
        stored = self.stored_data("cpu_limit")
        if not stored:
            stored = self.spec_mcpu()
            if not stored:
                raise AdaptationError("No CPU limits found in the Deployment spec")
            self.store_data("cpu_limit", stored)
        try:
            return int(stored)
        except (TypeError, ValueError) as error:
            raise AdaptationError("Invalid stored cpu_limit") from error

    def _csa_status(self) -> dict[str, Any] | None:
        try:
            resource = self.custom.get_namespaced_custom_object_status(
                group=CUSTOM_ADAPTER_GROUP,
                version=CUSTOM_ADAPTER_VERSION,
                namespace=self.namespace,
                plural=CUSTOM_ADAPTER_PLURAL,
                name=self.csa_name,
            )
        except ApiException as error:
            raise AdaptationError(
                f"Failed to read CustomSelfAdapter status {self.namespace}/{self.csa_name}"
            ) from error
        status = resource.get("status", {})
        return status if isinstance(status, dict) else None


def parse_cpu_mcpu(value: Any) -> int | None:
    if value is None:
        return None
    try:
        parsed = parse_quantity(str(value)) * 1000
        return int(round(parsed)) if math.isfinite(float(parsed)) else None
    except (ArithmeticError, TypeError, ValueError):
        return None


def format_cpu_mcpu(value: int) -> str:
    return f"{int(value)}m"
