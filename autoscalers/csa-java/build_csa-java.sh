#!/bin/bash

set -euo pipefail

export IMAGE="${IMAGE:-registry.k8s.lab/csa-znn-java}"
export TAG="${TAG:-vq}"

docker build -t "$IMAGE:$TAG" .
docker push "$IMAGE:$TAG"

envsubst < custom-selfadapter-template.yaml > "custom-selfadapter-${TAG}.yaml"
