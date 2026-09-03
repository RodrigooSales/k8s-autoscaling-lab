import json
import os
import sys
import types
from decimal import Decimal, InvalidOperation


payload = json.load(sys.stdin)
state = payload["state"]


class Namespace:
    def __init__(self, **values):
        self.__dict__.update(values)


class V1Deployment:
    pass


class V1Pod:
    pass


def container(cpu):
    return Namespace(
        name="znn",
        image="registry.k8s.lab/kube-znn:800k",
        resources=Namespace(limits={"cpu": f"{cpu}m"}),
    )


def deployment():
    replicas = payload["stdin"]["resource"]["spec"]["replicas"]
    value = V1Deployment()
    value.metadata = Namespace(generation=1)
    value.status = Namespace(
        observed_generation=1,
        updated_replicas=replicas,
        available_replicas=replicas,
    )
    value.spec = Namespace(
        replicas=replicas,
        selector=Namespace(match_labels={"app": "kube-znn"}),
        template=Namespace(
            spec=Namespace(containers=[container(state.get("spec_mcpu", 500))])
        ),
    )
    return value


class AppsV1Api:
    def read_namespaced_deployment(self, name, namespace):
        if state.get("kubernetes_error"):
            raise RuntimeError("Kubernetes unavailable")
        if state.get("deployment_missing"):
            return None
        return deployment()


class CoreV1Api:
    def list_namespaced_pod(self, namespace, label_selector=None):
        if state.get("kubernetes_error"):
            raise RuntimeError("Kubernetes unavailable")
        current = state.get("current_mcpu")
        if current is None:
            return Namespace(items=[])
        pod = V1Pod()
        pod.metadata = Namespace(name="kube-znn-1", namespace="default", annotations={})
        pod.spec = Namespace(containers=[container(current)])
        return Namespace(items=[pod])

    def read_namespaced_pod(self, name, namespace):
        pod = V1Pod()
        pod.metadata = Namespace(name=name, namespace=namespace, annotations={})
        return pod

    def patch_namespaced_pod(self, name, namespace, body):
        return body


class CustomObjectsApi:
    def get_namespaced_custom_object_status(self, group, version, namespace, plural, name):
        initial = state.get("initial_mcpu")
        status = {} if initial is None else {"initialData": json.dumps({"cpu_limit": initial})}
        return {"status": status}


exponents = {
    "n": -3,
    "u": -2,
    "m": -1,
    "K": 1,
    "k": 1,
    "M": 2,
    "G": 3,
    "T": 4,
    "P": 5,
    "E": 6,
}


def parse_quantity(quantity):
    if isinstance(quantity, (int, float, Decimal)):
        return Decimal(quantity)
    quantity = str(quantity)
    number = quantity
    suffix = None
    if len(quantity) >= 2 and quantity[-1] == "i" and quantity[-2] in exponents:
        number = quantity[:-2]
        suffix = quantity[-2:]
    elif len(quantity) >= 1 and quantity[-1] in exponents:
        number = quantity[:-1]
        suffix = quantity[-1:]
    try:
        number = Decimal(number)
    except InvalidOperation as error:
        raise ValueError(f"Invalid number format: {number}") from error
    if suffix is None:
        return number
    if suffix == "ki":
        raise ValueError(f"{quantity} has unknown suffix")
    base = 1024 if suffix.endswith("i") else 1000
    return number * (base ** Decimal(exponents[suffix[0]]))


kubernetes = types.ModuleType("kubernetes")
client = types.ModuleType("kubernetes.client")
models = types.ModuleType("kubernetes.client.models")
config = types.ModuleType("kubernetes.config")
utils = types.ModuleType("kubernetes.utils")
quantity = types.ModuleType("kubernetes.utils.quantity")
yaml = types.ModuleType("yaml")

client.CoreV1Api = CoreV1Api
client.AppsV1Api = AppsV1Api
client.CustomObjectsApi = CustomObjectsApi
models.V1Deployment = V1Deployment
models.V1Pod = V1Pod
config.load_incluster_config = lambda: None
quantity.parse_quantity = parse_quantity
yaml.safe_load = lambda stream: payload["config"]

kubernetes.client = client
kubernetes.config = config
kubernetes.utils = utils
client.models = models
utils.quantity = quantity

sys.modules["kubernetes"] = kubernetes
sys.modules["kubernetes.client"] = client
sys.modules["kubernetes.client.models"] = models
sys.modules["kubernetes.config"] = config
sys.modules["kubernetes.utils"] = utils
sys.modules["kubernetes.utils.quantity"] = quantity
sys.modules["yaml"] = yaml

sys.path.insert(0, os.environ["CSA_PYTHON_SCRIPTS"])
os.environ["CSA_NAME"] = "csa-test"
os.environ["CSA_NAMESPACE"] = "default"

import evaluate

evaluate.load_config = lambda: payload["config"]
evaluate.main(json.dumps(payload["stdin"]))
