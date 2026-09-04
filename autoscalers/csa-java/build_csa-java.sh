#!/bin/bash
set -eu

export IMAGE="${IMAGE:-registry.k8s.lab/csa-znn-java}"
export TAG="${TAG:-vq}"

case "$TAG" in
    h|hq|v|vq) ;;
    *)
        echo "TAG must be one of: h, hq, v, vq" >&2
        exit 2
        ;;
esac

docker build --build-arg TAG="$TAG" -t "$IMAGE:$TAG" .
docker push "$IMAGE:$TAG"

envsubst < custom-selfadapter-template.yaml > "custom-selfadapter-${TAG}.yaml"
