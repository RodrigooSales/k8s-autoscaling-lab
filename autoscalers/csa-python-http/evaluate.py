import math
from typing import Any

from metrics import MetricSnapshot


STRATEGY_REPLICAS = "adapt_replicas"
STRATEGY_CPU = "adapt_cpu"
STRATEGY_TAG = "adapt_tag"


class EvaluationError(ValueError):
    pass


def evaluate(
    snapshot: MetricSnapshot,
    config: dict[str, Any],
    current_mcpu: int,
    initial_mcpu: int,
) -> dict[str, Any] | None:
    if snapshot.target_metric_value == 0:
        raise EvaluationError("target metric is zero")

    rate = snapshot.current_metric_value / (snapshot.target_metric_value * 1000)
    enabled_strategies = config.get("enabled_strategies", (STRATEGY_REPLICAS,))
    min_replicas = config.get("minReplicas", 1)
    max_replicas = config.get("maxReplicas", 10)
    max_cpu = config.get("maxCPU", 1000)
    desired_replicas = math.ceil(snapshot.current_replicas * rate)

    if rate >= 0.95:
        if STRATEGY_REPLICAS in enabled_strategies and snapshot.current_replicas < max_replicas:
            return {
                "strategy": STRATEGY_REPLICAS,
                "parameters": {"replicas": min(desired_replicas, max_replicas)},
            }
        if STRATEGY_CPU in enabled_strategies and current_mcpu < max_cpu:
            return {"strategy": STRATEGY_CPU, "parameters": {"cpu_multiplier": rate}}
        if STRATEGY_TAG in enabled_strategies:
            return {
                "strategy": STRATEGY_TAG,
                "parameters": {
                    "tag_up": False,
                    "update_cpu": STRATEGY_CPU in enabled_strategies,
                },
            }

    if rate < 0.90:
        if STRATEGY_REPLICAS in enabled_strategies and snapshot.current_replicas > min_replicas:
            return {
                "strategy": STRATEGY_REPLICAS,
                "parameters": {"replicas": max(desired_replicas, min_replicas)},
            }
        if STRATEGY_CPU in enabled_strategies and current_mcpu > initial_mcpu:
            return {"strategy": STRATEGY_CPU, "parameters": {"cpu_multiplier": rate}}
        if STRATEGY_TAG in enabled_strategies:
            return {"strategy": STRATEGY_TAG, "parameters": {"tag_up": True}}

    return None
