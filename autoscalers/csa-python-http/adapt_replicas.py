from kubernetes import client
from kubernetes.client.rest import ApiException

import metrics


class AdaptationError(RuntimeError):
    pass


def adapt_replicas(replicas: int) -> dict[str, int]:
    if not isinstance(replicas, int):
        raise AdaptationError("replicas must be an integer")

    try:
        namespace = metrics._required_setting("CSA_NAMESPACE")
        deployment_name = metrics._required_setting("CSA_DEPLOYMENT_NAME")
        api_client = metrics._get_kubernetes_api_client()
    except metrics.MetricCollectionError as error:
        raise AdaptationError("Failed to configure Kubernetes client") from error

    try:
        client.AppsV1Api(api_client).patch_namespaced_deployment(
            name=deployment_name,
            namespace=namespace,
            body={"spec": {"replicas": replicas}},
        )
    except ApiException as error:
        raise AdaptationError(
            f"Failed to patch Deployment {namespace}/{deployment_name}"
        ) from error

    return {"replicas": replicas}
