FROM registry.k8s.lab/custom-self-adapter:alpine-latest@sha256:b10f5b6536fb08425c25c4143f39ba44732df984399471e0228b8dfe48095647

WORKDIR /app
ARG TAG=vq

COPY --chmod=0755 bin/csa-go /app/csa-go
COPY profiles/${TAG}.yaml /config.yaml
