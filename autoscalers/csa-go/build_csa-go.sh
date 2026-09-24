#!/usr/bin/env bash
set -eu

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

export IMAGE="${IMAGE:-registry.k8s.lab/csa-znn-go}"
export TAG="${TAG:-vq}"

case "$TAG" in
    h|hq|v|vq) ;;
    *)
        echo "TAG must be one of: h, hq, v, vq" >&2
        exit 2
        ;;
esac

command -v envsubst >/dev/null
mkdir -p bin
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 mise exec -- go build -trimpath -buildvcs=false -o bin/csa-go ./cmd/csa-go
docker build --platform linux/amd64 --build-arg TAG="$TAG" -t "$IMAGE:$TAG" .
docker push "$IMAGE:$TAG"
envsubst '${IMAGE} ${TAG}' < custom-selfadapter-template.yaml > "custom-selfadapter-${TAG}.yaml"
