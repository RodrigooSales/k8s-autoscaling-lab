FROM registry.k8s.lab/custom-self-adapter:alpine-latest@sha256:b10f5b6536fb08425c25c4143f39ba44732df984399471e0228b8dfe48095647

ARG PROFILE=h
COPY profiles/${PROFILE}.yaml /config.yaml
