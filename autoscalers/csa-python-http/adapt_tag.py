import re
from typing import Any

from kubernetes.client.rest import ApiException

from adapt_state import AdaptationContext, AdaptationError, format_cpu_mcpu

TAGS = ("100k", "200k", "400k", "600k", "800k")


def adapt_tag(tag_up: Any, update_cpu: Any = False) -> dict[str, str]:
    if not isinstance(tag_up, bool):
        raise AdaptationError("tag_up must be a boolean")
    if not isinstance(update_cpu, bool):
        raise AdaptationError("update_cpu must be a boolean")

    context = AdaptationContext()
    if context.rollout_in_progress():
        return {"result": "skip"}

    container = _container(context.deployment, "znn")
    if container is None:
        raise AdaptationError("Container 'znn' not found in Deployment")
    parts = _image_parts(container.image)
    if parts is None or not parts["tag"]:
        raise AdaptationError(f"Could not identify tag in image {container.image}")

    current_tag = parts["tag"]
    initial_tag = context.stored_data("tag") or current_tag
    context.store_data("tag", current_tag)
    if tag_up and initial_tag in TAGS and current_tag == initial_tag:
        return {"result": "skip"}

    try:
        index = TAGS.index(current_tag)
    except ValueError:
        return {"result": "skip"}
    next_index = index + (1 if tag_up else -1)
    if next_index < 0 or next_index >= len(TAGS):
        return {"result": "skip"}
    new_tag = TAGS[next_index]
    container.image = _replace_tag(container.image, new_tag)

    if update_cpu:
        initial_mcpu = context.initial_mcpu()
        current_mcpu = context.current_mcpu()
        if current_mcpu != initial_mcpu:
            for deployment_container in context.deployment.spec.template.spec.containers:
                if deployment_container.resources is None:
                    continue
                if deployment_container.resources.limits is None:
                    deployment_container.resources.limits = {}
                deployment_container.resources.limits["cpu"] = format_cpu_mcpu(current_mcpu)

    try:
        context.apps.patch_namespaced_deployment(
            name=context.deployment_name,
            namespace=context.namespace,
            body=context.deployment,
        )
    except ApiException as error:
        raise AdaptationError(
            f"Failed to patch Deployment {context.namespace}/{context.deployment_name}"
        ) from error
    return {"tag": new_tag}


def _container(deployment: Any, name: str) -> Any | None:
    for container in deployment.spec.template.spec.containers:
        if container.name == name:
            return container
    return None


def _image_parts(image: str) -> dict[str, str] | None:
    if not isinstance(image, str):
        return None
    match = re.match(
        r"^(?P<repository>[\w.\-_]+(?::\d+)?(?=/[a-z0-9._-]+/[a-z0-9._-]+)|)"
        r"(?:/|)(?P<image>[a-z0-9.\-_]+(?:/[a-z0-9.\-_]+|))"
        r":(?P<tag>[\w.\-_]{1,127})$",
        image,
    )
    return match.groupdict() if match else None


def _replace_tag(image: str, tag: str) -> str:
    parts = _image_parts(image)
    if parts is None:
        return image
    prefix = f"{parts['repository']}/" if parts["repository"] else ""
    return f"{prefix}{parts['image']}:{tag}"
