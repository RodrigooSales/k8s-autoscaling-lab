FROM golang:1.25-alpine AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY cmd/csa-go-http ./cmd/csa-go-http
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -buildvcs=false -ldflags="-s -w" -o /csa-go-http ./cmd/csa-go-http

FROM scratch

COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /csa-go-http /csa-go-http

ARG TAG=h
COPY profiles/${TAG}.yaml /config.yaml

USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/csa-go-http"]
