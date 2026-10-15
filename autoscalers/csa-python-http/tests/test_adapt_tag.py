import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import adapt_tag


def test_tag_adapter_patches_deployment_to_next_lower_quality(monkeypatch):
    znn = SimpleNamespace(name="znn", image="registry.k8s.lab/kube-znn:600k", resources=None)
    deployment = SimpleNamespace(
        spec=SimpleNamespace(
            template=SimpleNamespace(spec=SimpleNamespace(containers=[znn]))
        )
    )
    patched = []
    context = SimpleNamespace(
        rollout_in_progress=lambda: False,
        deployment=deployment,
        deployment_name="kube-znn",
        namespace="default",
        stored_data=lambda _field: "100k",
        store_data=lambda _field, _value: None,
        apps=SimpleNamespace(
            patch_namespaced_deployment=lambda **request: patched.append(request)
        ),
    )
    monkeypatch.setattr(adapt_tag, "AdaptationContext", lambda: context)

    result = adapt_tag.adapt_tag(False)

    assert result == {"tag": "400k"}
    assert znn.image == "registry.k8s.lab/kube-znn:400k"
    assert patched == [
        {"name": "kube-znn", "namespace": "default", "body": deployment}
    ]


def test_tag_adapter_restores_cpu_limits_when_requested(monkeypatch):
    containers = [
        SimpleNamespace(
            name=name,
            image=f"k8s.lab/{name}:600k",
            resources=SimpleNamespace(limits={"cpu": "750m"}),
        )
        for name in ("znn", "nginx")
    ]
    deployment = SimpleNamespace(
        spec=SimpleNamespace(
            template=SimpleNamespace(spec=SimpleNamespace(containers=containers))
        )
    )
    context = SimpleNamespace(
        rollout_in_progress=lambda: False,
        deployment=deployment,
        deployment_name="kube-znn",
        namespace="default",
        stored_data=lambda field: 100 if field == "tag" else 150,
        store_data=lambda _field, _value: None,
        initial_mcpu=lambda: 150,
        current_mcpu=lambda: 500,
        apps=SimpleNamespace(patch_namespaced_deployment=lambda **_request: None),
    )
    monkeypatch.setattr(adapt_tag, "AdaptationContext", lambda: context)

    result = adapt_tag.adapt_tag(False, update_cpu=True)

    assert result == {"tag": "400k"}
    assert [container.resources.limits["cpu"] for container in containers] == [
        "500m",
        "500m",
    ]


def test_tag_adapter_does_not_increase_quality_above_initial_tag(monkeypatch):
    container = SimpleNamespace(name="znn", image="k8s.lab/kube-znn:400k")
    deployment = SimpleNamespace(
        spec=SimpleNamespace(
            template=SimpleNamespace(spec=SimpleNamespace(containers=[container]))
        )
    )
    context = SimpleNamespace(
        rollout_in_progress=lambda: False,
        deployment=deployment,
        stored_data=lambda _field: "400k",
        store_data=lambda _field, _value: None,
        apps=SimpleNamespace(
            patch_namespaced_deployment=lambda **_request: (_ for _ in ()).throw(
                AssertionError("must not patch")
            )
        ),
    )
    monkeypatch.setattr(adapt_tag, "AdaptationContext", lambda: context)

    assert adapt_tag.adapt_tag(True) == {"result": "skip"}
