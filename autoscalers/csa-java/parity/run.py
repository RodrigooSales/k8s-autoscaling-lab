#!/usr/bin/env python3
import argparse
import copy
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

import yaml


CSA_JAVA = Path(__file__).resolve().parents[1]
REPOSITORY = CSA_JAVA.parents[1]
PYTHON_SCRIPTS = REPOSITORY / "autoscalers" / "csa" / "scripts"
JAVA = CSA_JAVA / "build" / "install" / "csa-java" / "bin" / "csa-java"
LOG = Path("/tmp/adapter.log")
TIMESTAMP = re.compile(r"(?m)^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3} ")


@dataclass(frozen=True)
class Case:
    name: str
    mode: str
    stdin: object
    state: dict
    failures: tuple = ()
    config: dict = None
    identity: bool = True


@dataclass(frozen=True)
class Result:
    stdin: str
    stdout: str
    stderr: str
    exit_code: int
    log: str
    log_writes: int
    trace: list
    state: dict


def deployment(image="registry.k8s.lab/kube-znn:600k", cpu="500m", replicas=2):
    return {
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {
            "name": "kube-znn",
            "namespace": "default",
            "generation": 4,
            "resourceVersion": "9",
        },
        "spec": {
            "replicas": replicas,
            "selector": {"matchLabels": {"app": "kube-znn"}},
            "template": {
                "metadata": {"labels": {"app": "kube-znn"}},
                "spec": {
                    "containers": [
                        {
                            "name": "znn",
                            "image": image,
                            "resources": {"limits": {"cpu": cpu}},
                        },
                        {
                            "name": "nginx",
                            "image": "nginx:1.27",
                            "resources": {"limits": {"cpu": cpu}},
                        },
                    ]
                },
            },
        },
        "status": {
            "observedGeneration": 4,
            "updatedReplicas": replicas,
            "availableReplicas": replicas,
        },
    }


def pod(name, cpu="500m"):
    return {
        "apiVersion": "v1",
        "kind": "Pod",
        "metadata": {"name": name, "namespace": "default"},
        "spec": {
            "containers": [
                {"name": "znn", "resources": {"limits": {"cpu": cpu}}},
                {"name": "nginx", "resources": {"limits": {"cpu": cpu}}},
            ]
        },
    }


def state(image="registry.k8s.lab/kube-znn:600k", cpu="500m", initial=None, replicas=2):
    if initial is None:
        initial = {"tag": "600k", "cpu_limit": 500}
    status = {} if initial == {} else {"initialData": json.dumps(initial, separators=(",", ":"))}
    return {
        "deployment": deployment(image, cpu, replicas),
        "pods": [pod("pod-a", cpu), pod("pod-b", cpu)],
        "self_pod": {
            "apiVersion": "v1",
            "kind": "Pod",
            "metadata": {
                "name": "csa-znn",
                "namespace": "default",
                "resourceVersion": "11",
                "annotations": {"unrelated": "keep"},
            },
            "spec": {"containers": [{"name": "csa", "image": "csa:test"}]},
        },
        "csa_status": status,
    }


def resource(replicas=2):
    return {
        "metadata": {"name": "kube-znn", "namespace": "default"},
        "spec": {"replicas": replicas},
    }


def cases():
    metric = {
        "resource": {"spec": {"replicas": 2}},
        "kubernetesMetrics": [
            {
                "spec": {"external": {"target": {"value": "1000"}}},
                "external": {"current": {"value": "1300000"}},
            }
        ],
    }
    evaluate = {
        "resource": resource(),
        "metrics": [
            {
                "value": json.dumps(
                    {"current_replicas": 2, "target_value": "1000", "current_value": "1300000"},
                    separators=(",", ":"),
                )
            }
        ],
    }
    replica = {
        "resource": resource(),
        "evaluation": {"parameters": {"replicas": 3}},
    }
    cpu = {
        "resource": resource(),
        "evaluation": {"parameters": {"cpu_multiplier": 1.5}},
    }
    cpu_state = state(cpu="103m", initial={"tag": "600k", "cpu_limit": 100})
    cpu_state["deployment"] = deployment(cpu="100m")
    tag = {
        "resource": resource(),
        "evaluation": {"parameters": {"tag_up": False, "update_cpu": False}},
    }
    lag = {
        "resource": resource(),
        "evaluation": {"parameters": {"tag_up": False, "update_cpu": True}},
    }
    all_cases = [
        Case("metric.basic", "metric", metric, state()),
        Case("evaluate.high_cpu", "evaluate", evaluate, state()),
        Case("replicas.success", "adapt_replicas", replica, state()),
        Case("cpu.half_even", "adapt_cpu", cpu, cpu_state),
        Case(
            "tag.registry_port",
            "adapt_tag",
            tag,
            state(image="registry.k8s.lab:5000/project/kube-znn:600k"),
        ),
        Case("initial_data.reconciliation_lag", "adapt_tag", lag, state(initial={})),
    ]

    for name, current in (("low_boundary", "900000"), ("deadband_upper", "949000"), ("high_boundary", "950000")):
        value = copy.deepcopy(evaluate)
        value["metrics"][0]["value"] = json.dumps(
            {"current_replicas": 2, "target_value": "1000", "current_value": current},
            separators=(",", ":"),
        )
        all_cases.append(Case(f"evaluate.{name}", "evaluate", value, state()))

    low_cpu = copy.deepcopy(evaluate)
    low_cpu["metrics"][0]["value"] = json.dumps(
        {"current_replicas": 2, "target_value": "1000", "current_value": "800000"},
        separators=(",", ":"),
    )
    all_cases.append(
        Case(
            "evaluate.low_cpu",
            "evaluate",
            low_cpu,
            state(cpu="700m", initial={"tag": "600k", "cpu_limit": 500}),
        )
    )
    high_tag_state = state(cpu="750m", initial={"tag": "600k", "cpu_limit": 500})
    all_cases.append(Case("evaluate.high_tag_fallback", "evaluate", evaluate, high_tag_state))
    all_cases.append(Case("evaluate.low_tag_fallback", "evaluate", low_cpu, state()))
    all_cases.extend(
        [
            Case(
                "evaluate.deployment_failure",
                "evaluate",
                evaluate,
                state(),
                ("deployment.get",),
            ),
            Case("evaluate.pod_failure", "evaluate", evaluate, state(), ("pods.list",)),
        ]
    )

    replica_config = {"enabled_strategies": ["adapt_replicas"]}
    for name, replicas, current in (("replicas_max", 4, "2000000"), ("replicas_min", 5, "100000")):
        value = copy.deepcopy(evaluate)
        value["resource"] = resource(replicas)
        value["metrics"][0]["value"] = json.dumps(
            {"current_replicas": replicas, "target_value": "1000", "current_value": current},
            separators=(",", ":"),
        )
        all_cases.append(
            Case(
                f"evaluate.{name}",
                "evaluate",
                value,
                state(replicas=replicas),
                config=replica_config,
            )
        )

    profile_hq = copy.deepcopy(evaluate)
    profile_hq["resource"] = resource(5)
    profile_hq_state = state(cpu="750m", replicas=5)
    all_cases.extend(
        [
            Case(
                "evaluate.profile_h",
                "evaluate",
                evaluate,
                state(),
                config={"enabled_strategies": ["adapt_replicas"]},
            ),
            Case(
                "evaluate.profile_hq",
                "evaluate",
                profile_hq,
                profile_hq_state,
                config={"enabled_strategies": ["adapt_replicas", "adapt_tag"]},
            ),
            Case(
                "evaluate.profile_v",
                "evaluate",
                evaluate,
                state(),
                config={"enabled_strategies": ["adapt_cpu"]},
            ),
            Case("evaluate.profile_vq", "evaluate", evaluate, state()),
            Case("evaluate.no_pods", "evaluate", evaluate, {**state(), "pods": []}),
            Case("evaluate.first_without_initial_data", "evaluate", low_cpu, state(initial={})),
        ]
    )
    invalid_metric = copy.deepcopy(metric)
    invalid_metric.pop("kubernetesMetrics")
    all_cases.append(Case("metric.missing_field", "metric", invalid_metric, state()))

    replica_bool = copy.deepcopy(replica)
    replica_bool["evaluation"]["parameters"]["replicas"] = True
    replica_invalid = copy.deepcopy(replica)
    replica_invalid["evaluation"]["parameters"]["replicas"] = "3"
    replica_missing = copy.deepcopy(replica)
    replica_missing["evaluation"]["parameters"] = {}
    replica_decimal = copy.deepcopy(replica)
    replica_decimal["evaluation"]["parameters"]["replicas"] = 3.5
    all_cases.extend(
        [
            Case("replicas.boolean", "adapt_replicas", replica_bool, state()),
            Case("replicas.invalid", "adapt_replicas", replica_invalid, state()),
            Case("replicas.missing", "adapt_replicas", replica_missing, state()),
            Case("replicas.decimal", "adapt_replicas", replica_decimal, state()),
            Case(
                "replicas.patch_failure",
                "adapt_replicas",
                replica,
                state(),
                ("deployment.patch",),
            ),
            Case(
                "replicas.deployment_failure",
                "adapt_replicas",
                replica,
                state(),
                ("deployment.get",),
            ),
        ]
    )

    cpu_base = {
        "resource": resource(),
        "evaluation": {"parameters": {"cpu_multiplier": 0.5}},
    }
    cpu_max = copy.deepcopy(cpu_base)
    cpu_max["evaluation"]["parameters"]["cpu_multiplier"] = 2
    cpu_rollout = state()
    cpu_rollout["deployment"]["status"]["observedGeneration"] = 3
    cpu_empty = state()
    cpu_empty["pods"] = []
    cpu_boolean = copy.deepcopy(cpu_base)
    cpu_boolean["evaluation"]["parameters"]["cpu_multiplier"] = True
    cpu_mixed = state(initial={"tag": "600k", "cpu_limit": 500})
    cpu_mixed["pods"] = [pod("pod-a", "500m"), pod("pod-b", "700m")]
    all_cases.extend(
        [
            Case(
                "cpu.baseline_clamp",
                "adapt_cpu",
                cpu_base,
                state(cpu="600m", initial={"tag": "600k", "cpu_limit": 500}),
            ),
            Case(
                "cpu.maximum_clamp",
                "adapt_cpu",
                cpu_max,
                state(cpu="700m", initial={"tag": "600k", "cpu_limit": 500}),
            ),
            Case("cpu.rollout", "adapt_cpu", cpu_base, cpu_rollout),
            Case("cpu.no_pods", "adapt_cpu", cpu_base, cpu_empty),
            Case("cpu.boolean", "adapt_cpu", cpu_boolean, state()),
            Case("cpu.mixed_pods", "adapt_cpu", cpu_max, cpu_mixed),
            Case(
                "cpu.deployment_failure",
                "adapt_cpu",
                cpu_base,
                state(),
                ("deployment.get",),
            ),
            Case("cpu.first_list_failure", "adapt_cpu", cpu_base, state(), ("pods.list.1",)),
            Case("cpu.second_list_failure", "adapt_cpu", cpu_base, state(), ("pods.list.2",)),
            Case(
                "cpu.first_resize_failure",
                "adapt_cpu",
                cpu_base,
                state(cpu="600m", initial={"tag": "600k", "cpu_limit": 500}),
                ("pod.resize.pod-a",),
            ),
            Case(
                "cpu.partial_failure",
                "adapt_cpu",
                cpu_base,
                state(cpu="600m", initial={"tag": "600k", "cpu_limit": 500}),
                ("pod.resize.pod-b",),
            ),
        ]
    )

    tag_up = copy.deepcopy(tag)
    tag_up["evaluation"]["parameters"]["tag_up"] = True
    tag_cpu = copy.deepcopy(tag)
    tag_cpu["evaluation"]["parameters"]["update_cpu"] = True
    tag_rollout = state()
    tag_rollout["deployment"]["status"]["observedGeneration"] = 3
    tag_empty = state()
    tag_empty["pods"] = []
    tag_missing_container = state()
    tag_missing_container["deployment"]["spec"]["template"]["spec"]["containers"] = [
        tag_missing_container["deployment"]["spec"]["template"]["spec"]["containers"][1]
    ]
    tag_missing_parameter = copy.deepcopy(tag)
    tag_missing_parameter["evaluation"]["parameters"].pop("tag_up")
    all_cases.extend(
        [
            Case(
                "tag.digest",
                "adapt_tag",
                tag,
                state(image="registry.k8s.lab/kube-znn@sha256:abcdef"),
            ),
            Case("tag.without_tag", "adapt_tag", tag, state(image="registry.k8s.lab/kube-znn")),
            Case("tag.unknown", "adapt_tag", tag, state(image="registry.k8s.lab/kube-znn:latest")),
            Case("tag.lower_boundary", "adapt_tag", tag, state(image="registry.k8s.lab/kube-znn:100k")),
            Case("tag.initial_boundary", "adapt_tag", tag_up, state()),
            Case("tag.rollout", "adapt_tag", tag, tag_rollout),
            Case("tag.missing_container", "adapt_tag", tag, tag_missing_container),
            Case("tag.missing_parameter", "adapt_tag", tag_missing_parameter, state()),
            Case("tag.no_pods", "adapt_tag", tag_cpu, tag_empty),
            Case(
                "tag.update_cpu",
                "adapt_tag",
                tag_cpu,
                state(cpu="700m", initial={"tag": "600k", "cpu_limit": 500}),
            ),
            Case("tag.list_failure", "adapt_tag", tag_cpu, state(), ("pods.list",)),
            Case("tag.patch_failure", "adapt_tag", tag, state(), ("deployment.patch",)),
            Case(
                "tag.deployment_failure",
                "adapt_tag",
                tag,
                state(),
                ("deployment.get",),
            ),
            Case("initial_data.status_failure", "adapt_tag", tag, state(initial={}), ("status.get",)),
            Case("initial_data.patch_failure", "adapt_tag", tag, state(initial={}), ("self_pod.patch",)),
            Case(
                "initial_data.sequential_writes",
                "adapt_tag",
                tag_cpu,
                state(initial={"cpu_limit": None}),
            ),
            Case("process.metric_malformed", "metric", "{", state()),
            Case("process.evaluate_malformed", "evaluate", "{", state()),
            Case("process.replicas_malformed", "adapt_replicas", "{", state()),
            Case("process.cpu_malformed", "adapt_cpu", "{", state()),
            Case("process.tag_malformed", "adapt_tag", "{", state()),
        ]
    )

    for current_tag, direction_up in (
        ("100k", True),
        ("200k", False),
        ("200k", True),
        ("400k", False),
        ("400k", True),
        ("600k", False),
        ("600k", True),
        ("800k", False),
    ):
        transition = copy.deepcopy(tag)
        transition["evaluation"]["parameters"]["tag_up"] = direction_up
        initial_tag = "800k" if direction_up else "600k"
        all_cases.append(
            Case(
                f"tag.transition_{current_tag}_{'up' if direction_up else 'down'}",
                "adapt_tag",
                transition,
                state(
                    image=f"registry.k8s.lab/kube-znn:{current_tag}",
                    initial={"tag": initial_tag, "cpu_limit": 500},
                ),
            )
        )
    all_cases.append(
        Case(
            "tag.upper_boundary",
            "adapt_tag",
            tag_up,
            state(image="registry.k8s.lab/kube-znn:800k"),
        )
    )
    annotation_newer = state(initial={"cpu_limit": 500})
    annotation_newer["self_pod"]["metadata"]["annotations"][
        "csa.custom-self-adapter.net/initialData"
    ] = json.dumps({"tag": "800k"})
    all_cases.extend(
        [
            Case("initial_data.annotation_newer", "adapt_tag", tag, annotation_newer),
            Case("initial_data.missing_identity", "adapt_tag", tag, state(initial={}), identity=False),
        ]
    )
    return all_cases


class FakeKubernetes:
    def __init__(self, initial_state, failures=()):
        self.state = copy.deepcopy(initial_state)
        self.failures = set(failures)
        self.calls = {}
        self.trace = []
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self.handler())
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def url(self):
        return f"http://127.0.0.1:{self.server.server_port}"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def handler(self):
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                owner.request(self)

            def do_PATCH(self):
                owner.request(self)

            def log_message(self, *_):
                pass

        return Handler

    def request(self, request):
        parsed = urlsplit(request.path)
        raw_body = read_body(request)
        body = json.loads(raw_body) if raw_body else None
        content_type = request.headers.get("Content-Type", "").split(";", 1)[0] if body else ""
        self.trace.append(
            {
                "method": request.command,
                "path": parsed.path,
                "query": sorted(parse_qsl(parsed.query)),
                "content_type": content_type,
                "body": body,
            }
        )

        operation = self.operation(request.command, parsed.path)
        self.calls[operation] = self.calls.get(operation, 0) + 1
        if operation in self.failures or f"{operation}.{self.calls[operation]}" in self.failures:
            return self.respond(
                request,
                {
                    "apiVersion": "v1",
                    "kind": "Status",
                    "status": "Failure",
                    "message": "forced failure",
                    "reason": "InternalError",
                    "code": 500,
                },
                500,
            )

        deployment_path = "/apis/apps/v1/namespaces/default/deployments/kube-znn"
        pods_path = "/api/v1/namespaces/default/pods"
        self_pod_path = f"{pods_path}/csa-znn"
        status_path = (
            "/apis/custom-self-adapter.net/v1/namespaces/default/"
            "customselfadapters/csa-znn/status"
        )
        if request.command == "GET" and parsed.path == deployment_path:
            return self.respond(request, self.state["deployment"])
        if request.command == "PATCH" and parsed.path == deployment_path:
            merge(self.state["deployment"], body)
            return self.respond(request, self.state["deployment"])
        if request.command == "GET" and parsed.path == pods_path:
            return self.respond(
                request,
                {"apiVersion": "v1", "kind": "PodList", "items": self.state["pods"]},
            )
        if request.command == "GET" and parsed.path == self_pod_path:
            return self.respond(request, self.state["self_pod"])
        if request.command == "PATCH" and parsed.path == self_pod_path:
            merge(self.state["self_pod"], body)
            return self.respond(request, self.state["self_pod"])
        if request.command == "GET" and parsed.path == status_path:
            return self.respond(request, {"status": self.state["csa_status"]})
        if request.command == "PATCH" and parsed.path.startswith(pods_path + "/") and parsed.path.endswith("/resize"):
            name = parsed.path.split("/")[-2]
            selected = next(item for item in self.state["pods"] if item["metadata"]["name"] == name)
            merge(selected, body)
            return self.respond(request, selected)
        return self.respond(request, {"message": f"unexpected endpoint {request.command} {parsed.path}"}, 404)

    @staticmethod
    def operation(method, path):
        if path.endswith("/deployments/kube-znn"):
            return "deployment.get" if method == "GET" else "deployment.patch"
        if path.endswith("/customselfadapters/csa-znn/status"):
            return "status.get"
        if path.endswith("/pods/csa-znn"):
            return "self_pod.get" if method == "GET" else "self_pod.patch"
        if path.endswith("/resize"):
            return "pod.resize." + path.split("/")[-2]
        if path.endswith("/pods"):
            return "pods.list"
        return "unknown"

    @staticmethod
    def respond(request, body, status=200):
        encoded = json.dumps(body, separators=(",", ":")).encode()
        request.send_response(status)
        request.send_header("Content-Type", "application/json")
        request.send_header("Content-Length", str(len(encoded)))
        request.end_headers()
        request.wfile.write(encoded)

    def snapshot(self):
        return copy.deepcopy(self.state)


def merge(target, patch):
    for key, value in patch.items():
        if isinstance(value, dict) and isinstance(target.get(key), dict):
            merge(target[key], value)
        else:
            target[key] = copy.deepcopy(value)


def read_body(request):
    length = int(request.headers.get("Content-Length", 0))
    if length:
        return request.rfile.read(length)
    if request.headers.get("Transfer-Encoding", "").lower() != "chunked":
        return b""
    chunks = []
    while True:
        size = int(request.rfile.readline().split(b";", 1)[0], 16)
        if size == 0:
            request.rfile.readline()
            break
        chunks.append(request.rfile.read(size))
        request.rfile.read(2)
    return b"".join(chunks)


def execute(case, runtime):
    with FakeKubernetes(case.state, case.failures) as api:
        LOG.unlink(missing_ok=True)
        config_path = test_config(case.config)
        environment = os.environ.copy()
        environment.update(
            {
                "CSA_CONFIG_PATH": str(config_path),
                "CSA_KUBERNETES_URL": api.url,
                "CSA_NAME": "csa-znn",
                "CSA_NAMESPACE": "default",
            }
        )
        if not case.identity:
            environment.pop("CSA_NAME")
            environment.pop("CSA_NAMESPACE")
        if runtime == "python":
            environment["PYTHONPATH"] = os.pathsep.join(
                [str(CSA_JAVA / "parity"), str(PYTHON_SCRIPTS)]
            )
            command = [sys.executable, str(PYTHON_SCRIPTS / python_script(case.mode))]
        else:
            command = [str(JAVA), "-m", case.mode]
        stdin = case.stdin if isinstance(case.stdin, str) else json.dumps(case.stdin, separators=(",", ":"))
        process = subprocess.run(
            command,
            input=stdin,
            text=True,
            capture_output=True,
            env=environment,
            timeout=10,
            check=False,
        )
        log = LOG.read_text() if LOG.exists() else ""
        if config_path != CSA_JAVA / "config.yaml":
            config_path.unlink()
        return Result(
            stdin,
            process.stdout,
            normalize(process.stderr),
            process.returncode,
            normalize(log),
            len(TIMESTAMP.findall(log)),
            api.trace,
            api.snapshot(),
        )


def test_config(overrides):
    if not overrides:
        return CSA_JAVA / "config.yaml"
    config = yaml.safe_load((CSA_JAVA / "config.yaml").read_text())
    config.update(overrides)
    handle = tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False)
    with handle:
        yaml.safe_dump(config, handle)
    return Path(handle.name)


def python_script(mode):
    return {
        "metric": "metric_nginx_req_duration.py",
        "evaluate": "evaluate.py",
        "adapt_replicas": "adapt_replicas.py",
        "adapt_cpu": "adapt_cpu.py",
        "adapt_tag": "adapt_tag.py",
    }[mode]


def normalize(value):
    value = TIMESTAMP.sub("", value)
    value = re.sub(r"(?m)(Invalid JSON on stdin:).*$", r"\1 <runtime error>", value)
    value = re.sub(
        r"(?ms)(^.* -  ERROR - Failed to (?:patch|read) Deployment [^:\n]+:).*$",
        r"\1 <runtime error>\n",
        value,
    )
    value = re.sub(
        r"(?ms)(^adapt_cpu\s+-  ERROR - Failed to resize pod).*$",
        r"\1 <runtime error>\n",
        value,
    )
    traceback = value.find("Traceback (most recent call last):")
    java_error = re.search(r"(?m)^(?:java|com)\.[^\n]*(?:Exception|Error)", value)
    positions = [position for position in (traceback, java_error.start() if java_error else -1) if position >= 0]
    if positions:
        value = value[: min(positions)] + "<runtime traceback>\n"
    return value


def compare(case):
    python = execute(case, "python")
    java = execute(case, "java")
    if python == java:
        print(f"PASS {case.name}")
        return True
    print(f"FAIL {case.name}")
    for field in Result.__dataclass_fields__:
        expected = getattr(python, field)
        actual = getattr(java, field)
        if expected != actual:
            print(f"  {field}")
            print("    python:", json.dumps(expected, ensure_ascii=False, sort_keys=True))
            print("    java:  ", json.dumps(actual, ensure_ascii=False, sort_keys=True))
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--case")
    args = parser.parse_args()
    if not JAVA.exists():
        raise SystemExit("Java distribution missing; run ./gradlew installDist")
    selected = [case for case in cases() if args.case in (None, case.name)]
    if not selected:
        raise SystemExit(f"unknown case: {args.case}")
    original_log = LOG.read_bytes() if LOG.exists() else None
    try:
        failures = sum(not compare(case) for case in selected)
    finally:
        if original_log is None:
            LOG.unlink(missing_ok=True)
        else:
            LOG.write_bytes(original_log)
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
