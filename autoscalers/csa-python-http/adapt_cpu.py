import math
from typing import Any

from kubernetes.client.rest import ApiException

from adapt_state import AdaptationContext, AdaptationError, format_cpu_mcpu


def cpu_state() -> tuple[int, int]:
    context = AdaptationContext()
    pods = context.running_pods()
    if not pods:
        raise AdaptationError(
            f"Could not find Pods for Deployment {context.namespace}/{context.deployment_name}"
        )
    current_mcpu = context.current_mcpu(pods)
    initial_mcpu = context.initial_mcpu()
    return current_mcpu, initial_mcpu


def adapt_cpu(multiplier: Any, max_cpu: int = 1000) -> dict[str, int | str]:
    if isinstance(multiplier, bool) or not isinstance(multiplier, (int, float)):
        raise AdaptationError("cpu_multiplier must be a number")
    multiplier = float(multiplier)
    if not math.isfinite(multiplier):
        raise AdaptationError("cpu_multiplier must be finite")

    context = AdaptationContext()
    if context.rollout_in_progress():
        return {"result": "skip"}

    initial_mcpu = context.initial_mcpu()
    pods = context.running_pods()
    if not pods:
        raise AdaptationError(
            f"Could not find Pods for Deployment {context.namespace}/{context.deployment_name}"
        )
    current_mcpu = context.current_mcpu(pods)
    new_mcpu = round(current_mcpu * multiplier)
    new_mcpu = max(initial_mcpu, min(new_mcpu, int(max_cpu)))
    body = {
        "spec": {
            "containers": [
                {
                    "name": name,
                    "resources": {"limits": {"cpu": format_cpu_mcpu(new_mcpu)}},
                }
                for name in ("znn", "nginx")
            ]
        }
    }

    for pod in pods:
        try:
            context.core.patch_namespaced_pod_resize(
                name=pod.metadata.name,
                namespace=context.namespace,
                body=body,
            )
        except ApiException as error:
            raise AdaptationError(
                f"Failed to resize Pod {context.namespace}/{pod.metadata.name}"
            ) from error
    return {"cpu": new_mcpu}
