import os
from pathlib import Path
from typing import Any

import yaml
from fastapi import FastAPI, HTTPException, Response
from fastapi.responses import JSONResponse

import adapt_cpu
import adapt_replicas
import adapt_state
import adapt_tag
import metrics
from evaluate import evaluate

app = FastAPI(title="CSA Python HTTP", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/metric")
def metric_callback(spec: dict[str, Any]) -> dict[str, Any]:
    return metrics.metric_result(metrics.collect_metrics(spec))


@app.post("/evaluate")
def evaluate_callback(spec: dict[str, Any]) -> Response:
    snapshot = metrics.snapshot_from_evaluation(spec)
    profile = load_profile()
    current_mcpu = initial_mcpu = 0
    if "adapt_cpu" in profile.get("enabled_strategies", ()):
        try:
            current_mcpu, initial_mcpu = adapt_cpu.cpu_state()
        except adapt_state.AdaptationError as error:
            raise HTTPException(status_code=500, detail=str(error)) from error
    decision = evaluate(snapshot, profile, current_mcpu, initial_mcpu)
    if decision is None:
        return Response(status_code=204)
    return JSONResponse(content=decision)


@app.post("/adapt/replicas")
def adapt_replicas_callback(spec: dict[str, Any]) -> dict[str, int]:
    parameters = _nested_object(spec, "evaluation", "parameters")
    replicas = parameters.get("replicas")
    if not isinstance(replicas, int) or isinstance(replicas, bool):
        raise HTTPException(status_code=400, detail="evaluation.parameters.replicas must be an integer")
    try:
        return adapt_replicas.adapt_replicas(replicas)
    except adapt_replicas.AdaptationError as error:
        raise HTTPException(status_code=500, detail=str(error)) from error


@app.post("/adapt/cpu")
def adapt_cpu_callback(spec: dict[str, Any]) -> dict[str, int | str]:
    parameters = _nested_object(spec, "evaluation", "parameters")
    multiplier = parameters.get("cpu_multiplier")
    if isinstance(multiplier, bool) or not isinstance(multiplier, (int, float)):
        raise HTTPException(status_code=400, detail="cpu_multiplier must be a number")
    try:
        return adapt_cpu.adapt_cpu(multiplier, int(load_profile().get("maxCPU", 1000)))
    except adapt_state.AdaptationError as error:
        raise HTTPException(status_code=500, detail=str(error)) from error


@app.post("/adapt/tag")
def adapt_tag_callback(spec: dict[str, Any]) -> dict[str, str]:
    parameters = _nested_object(spec, "evaluation", "parameters")
    tag_up = parameters.get("tag_up")
    update_cpu = parameters.get("update_cpu", False)
    if not isinstance(tag_up, bool) or not isinstance(update_cpu, bool):
        raise HTTPException(status_code=400, detail="tag_up and update_cpu must be booleans")
    try:
        return adapt_tag.adapt_tag(tag_up, update_cpu)
    except adapt_state.AdaptationError as error:
        raise HTTPException(status_code=500, detail=str(error)) from error


def load_profile() -> dict[str, Any]:
    path = Path(os.getenv("CSA_PROFILE_PATH", "/config.yaml"))
    try:
        with path.open(encoding="utf-8") as profile_file:
            profile = yaml.safe_load(profile_file)
    except (OSError, yaml.YAMLError) as error:
        raise RuntimeError(f"Failed to load profile {path}") from error
    if not isinstance(profile, dict):
        raise RuntimeError(f"Profile {path} must contain a YAML object")
    return profile


def _nested_object(spec: dict[str, Any], *path: str) -> dict[str, Any]:
    value: Any = spec
    for field in path:
        value = value.get(field) if isinstance(value, dict) else None
    if not isinstance(value, dict):
        raise HTTPException(status_code=400, detail=f"{'/'.join(path)} must be an object")
    return value
