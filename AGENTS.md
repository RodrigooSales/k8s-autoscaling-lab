# Repository Guidelines

## Project Structure & Module Organization

`autoscalers/` holds Python, Java, and Go CSA code plus HPA, VPA, and CPA manifests. Java code/tests are in `autoscalers/csa-java/src/{main,test}/java`; the Go project is in `autoscalers/csa-go/`; shared contract fixtures are in `autoscalers/contracts/cases/`. Cluster definitions live in `charts/`, `values/`, `bootstrapping/`, and `helmfile_step*.yaml`. `kube-znn/` and `vagrant-kubeadm-kubernetes/` are submodules. Locust scenarios are in `tests/scenarios/`, outputs in `tests/results/`, and Python scripts analyze them.

The repository combines autoscaler configurations, the `kube-znn` sample application, the Vagrant Kubernetes lab, Locust scenarios and result analysis, plus root-level scripts for cluster setup and experiments. The root `README.md` documents the current setup and workflow.


## Dependencies

### CSA Java

- Java 21, built with the Gradle wrapper.
- Packages: JUnit 4, Kubernetes Java client, Gson, SnakeYAML, and SLF4J's no-op runtime binding.

### CSA and CPA Python

- Python packages: Kubernetes Python client and PyYAML.

### CSA Go

- Go 1.25, managed with mise.
- Packages: `k8s.io/client-go`, `k8s.io/api`, `k8s.io/apimachinery`, and `go.yaml.in/yaml/v2`. JSON, CLI parsing, logging, and tests use the Go standard library. `sigs.k8s.io/yaml` is an indirect Kubernetes dependency.

### Experiments and Analysis

- Python
- Locust 

### Local Kubernetes Lab

- The current Vagrant defaults documented in `README.md` use Kubernetes 1.35.3 and Calico networking.
- Host prerequisites are listed in `README.md`; they include Git, Vagrant, VirtualBox, Docker, kubectl, Helm, Helmfile, Python 3.12+, and Bash.

## Autoscalers

All benchmark autoscalers target the `kube-znn` Deployment. CSA and HPA use the external `znn_latency_ms_p95` metric with a target value of `1000`.

### CSA Python (`autoscalers/csa/`)

- **`config.yaml`** — Wires the metric, evaluation, and adaptation shell hooks. The checked-in profile runs every 5 seconds, permits 1–5 replicas and up to `750m` CPU, and enables CPU and tag adaptation.
- **`custom-selfadapter-{h,hq,v,vq}.yaml`** — Select the packaged horizontal, horizontal-plus-tag, vertical CPU, and vertical-plus-tag profiles. The manifests otherwise target the same Deployment and grant access to `pods/resize`.
- **`scripts/metric_nginx_req_duration.py`** — Passes the current replica count and external metric target/current values to the evaluator.
- **`scripts/evaluate.py`** — Computes the load ratio. At `>= 0.95`, it tries replicas, CPU, then a lower image tag; below `0.90`, it applies the inverse adjustments. The `0.90–0.95` band is a no-op, and disabled strategies are skipped.
- **`scripts/adapt_replicas.py`** — Patches `Deployment.spec.replicas` with the evaluator's bounded replica count.
- **`scripts/adapt_cpu.py`** — Resizes CPU limits for the running `znn` and `nginx` containers, bounded by the initial limit and `maxCPU`; skips active rollouts.
- **`scripts/adapt_tag.py`** — Moves the `znn` image through `100k`, `200k`, `400k`, `600k`, and `800k`; high load selects a lower tag, while spare capacity restores the initial quality.
- **`scripts/adapt_base.py`** — Provides shared stdin parsing, in-cluster clients, Deployment and Pod lookup, rollout checks, and CPU quantity conversion.
- **`scripts/initial_data.py`** — Persists the original image tag and CPU limit so later adaptations cannot cross their baseline.

### CSA-JAVA (`autoscalers/csa-java/`)

The Java port implements the same stdin/stdout strategy contract in one executable.

- **`config.yaml`** — Maps CSA phases to binary modes, polls every 5 seconds, bounds replicas at 1–5 and CPU at `750m`, and enables CPU and tag adaptation.
- **`src/main/java/org/csajava/App.java`** — Loads stdin and `/config.yaml`, validates the requested mode, dispatches it, and emits structured errors.
- **`src/main/java/org/csajava/runtime/ModeHandlers.java`** — Maps metric, evaluation, replica, CPU, and tag modes to their runtime handlers.
- **`src/main/java/org/csajava/runtime/metric/MetricRuntime.java`** — Validates and normalizes the external metric payload for evaluation.
- **`src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java`** — Mirrors the Python thresholds, strategy priority, replica calculation, and configured CPU/replica bounds.
- **`src/main/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntime.java`** — Applies horizontal scaling with a strategic merge patch on `spec.replicas`.
- **`src/main/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntime.java`** — Uses the Pod resize subresource for `znn` and `nginx`, clamping CPU between the stored baseline and `maxCPU`.
- **`src/main/java/org/csajava/runtime/adapt/tag/AdaptTagRuntime.java`** — Changes the image along the same tag ladder and skips changes during an active rollout.
- **`src/main/java/org/csajava/runtime/initialdata/InitialDataStore.java`** — Stores initial tag and CPU values through the CSA annotation/status contract.

### CSA Go (`autoscalers/csa-go/`)

The Go project is organized with its executable in `cmd/csa-go/` and implementation/tests in `internal/csa/`. All five modes (`metric`, `evaluate`, `adapt_replicas`, `adapt_cpu`, `adapt_tag`) have unit tests. Evaluation tries to persist the initial CPU through the CSA Pod annotation for the operator to reconcile into `status.initialData`; later adaptations read that state. Replica and tag adaptation patch the loaded Deployment with a strategic merge patch, and CPU adaptation resizes Pods through the `resize` subresource. Unit tests use shared cases in `autoscalers/contracts/cases/` and fake Kubernetes clients, without a cluster. `Dockerfile`, `profiles/{h,hq,v,vq}.yaml`, and `custom-selfadapter-{h,hq,v,vq}.yaml` package the Go runtime. `TAG=vq ./build_csa-go.sh` builds a static `linux/amd64` binary with mise, publishes it to the internal registry, and generates the matching manifest. The runtime base is pinned by digest. CPU/memory resources, workload target, node placement, and `pods/resize` permission match the Python and Java manifests; Go uses a distinct CSA/container name and image repository. Functional validation is recorded in `autoscalers/csa-go/docs/validacao-funcional.md`.

### HPA (`autoscalers/hpa/`)

- **`znn.yaml`** — Scales `kube-znn` from 1 to 5 replicas against p95 latency `1000`; Kubernetes default behavior policies apply.
- **`znn_fast.yaml`** — Uses the same target and bounds, but reduces scale-down stabilization to 10 seconds and allows a 100% reduction every 15 seconds.

### VPA (`autoscalers/vpa/`)

- **`znn.yaml`** — Recreates Pods to apply CPU recommendations for `znn` and `nginx`, controlling requests and limits between `150m` and `500m`; memory is untouched.

Note: CPA is no longer used by the test scenarios.

## Build, Test, and Development Commands

- `git submodule update --init --recursive` initializes submodules.
- `python -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt` creates the analysis environment.
- `(cd vagrant-kubeadm-kubernetes && vagrant up)` provisions the lab. Then export `KUBECONFIG="$PWD/vagrant-kubeadm-kubernetes/configs/config"` and run `./cluster_bootstrap.sh`.
- `cd autoscalers/csa-java && ./gradlew test` runs tests; `./gradlew build` builds Java.
- `cd autoscalers/csa-go && mise exec -- go run ./cmd/csa-go -m metric` runs the Go metric mode; `mise exec -- go build -o bin/csa-go ./cmd/csa-go` builds it; `mise exec -- go test ./...` runs Go tests; `TAG=vq ./build_csa-go.sh` builds and publishes the Go image and writes its manifest (requires Docker access to `registry.k8s.lab` and `envsubst`).
- From the repository root, activate `.venv` before using the load scripts so the pinned Locust executable and relative certificate path are available. Run load matrices only when the user explicitly authorizes the specific run: `source .venv/bin/activate && ./run_tests_by_cenary.sh csa-go 1` runs one CSA Go iteration; `./run_tests.sh 1` runs one full destructive matrix, and no argument means 50 iterations.

## Coding Style & Naming Conventions

Use four spaces for Python/Java and two for YAML. No global formatter or linter exists; follow adjacent code. Python uses `snake_case` files/functions and `UPPER_SNAKE_CASE` constants. Java uses `PascalCase` types, `camelCase` members, and lowercase packages. Go files use `gofmt`; use short lowercase package names and `MixedCaps` for exported identifiers.

## Testing Guidelines

Write a failing test before non-trivial behavior changes. JUnit 4 tests mirror production packages and end in `Test.java`; Go tests use the standard `testing` package in `_test.go` files. Shared contracts live in `autoscalers/contracts/cases/`. Unit tests should run without a cluster. Use cluster checks for functional integration changes; `run_tests*` execute destructive Locust load matrices and may run only when the user explicitly authorizes that specific run. No coverage threshold exists; run the relevant language test suite for changes.

## Visualization Guidelines

Keep CSA chart and legend labels in the established `CSA <implementation> <scenario>` format, such as `CSA Python H`, `CSA Java HQ 25`, and `CSA Go VQ`. Grouping by scenario must use the configuration order and must never be implemented by renaming or reformatting these labels.

## Commit & Pull Request Guidelines

- Keep all project work in the personal fork. Never open pull requests against the upstream/original repository.
- Never commit directly to the fork's `main` or any branch whose name starts with `csa-`. Work in a separate Git worktree or branch and open a pull request to the intended protected branch within the fork.
- Name working branches using Conventional Branches style: `<type>/<short-kebab-case-description>`, with types aligned to Conventional Commits, such as `feat/`, `fix/`, `docs/`, `refactor/`, `test/`, `chore/`, or `perf/`.
- Keep each pull request focused on one objective and limit it to at most 500 lines of production logic. Tests, documentation, and configuration do not count toward this limit. Split larger changes into separate pull requests.
- Prefer removing or simplifying existing code over adding new code.
- Document all work, decisions, relevant commands, and validation results in the appropriate project documentation.

Use short, imperative Conventional Commit subjects, for example `fix(csa-java): handle null evaluation`. Keep one logical change per commit and stage explicitly. Pull requests explain scope and cluster impact, link applicable issues, and list verification. Attach plots for visual changes; avoid unrelated bulk results.

## Security & Configuration

Verify the Kubernetes context before running scripts. Never commit private keys, kubeconfigs, registry credentials, Terraform state, or generated certificates.
