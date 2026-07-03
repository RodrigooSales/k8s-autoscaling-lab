# K8S Autoscaling Lab

This repository is used to create a Kubernetes Lab that allows to stabilish a cluster running on vagrant+virtualbox for executing various Autoscaling/Autoadapting solutions, in order to compare the Custom Self-Adapter with other Autoscaling solutions, as Horizontal Pod Autoscaler and Custom Pod Autoscaler.

# Dependencies

Ths cluster is deployed using vagrant on virtualbox.

The base components on the cluster (ingress, prometheus, grafana etc) are deployed using [helmfile](https://github.com/helmfile/helmfile).

# Cluster Init

On the `vagrant-kubeadm-kubernetes` directory, configure the nodes on `settings.yaml` and init the cluster with

```bash
vagrant up
```

On `settings.yaml`, the `custom_ca` option allows the execution of scripts that generate a root CA certificate and installs it on the nodes. This certificate will be used to sign the Ingress certificate, and should be installed on the host machine that runs the lab, so k6 can trust it. On a Debian system and derivatives, you should copy the certificate to `/usr/local/share/ca-certificates/` and then run:

```bash
update-ca-certificates
```

# Connect to the cluster

The cluster config file is saved under `vagrant-kubeadm-kubernetes/configs`. Add the `KUBECONFIG` env to connect to the cluster:

```bash
export KUBECONFIG=$PWD/vagrant-kubeadm-kubernetes/configs/config
```

# Helmfile & Bootstrapping

The `helmfile.yaml` file and the `bootstrapping` dir are used to deploy the base for the lab. The files in `bootstrapping` deploy components not deployed by the charts in the `helmfile_step1.yaml` and `helmfile_step2.yaml` files. The `cluster_bootstrap.sh` script executes the commands necessary to run the deloys.

```bash
./cluster_bootstrap.sh
```

# Update /etc/hosts

The registry used to store the application's image runs in the cluster's domain (k8s.lab). Their DNS entry and entries from other components should be added to the host's `/etc/hosts` file as:

```
10.0.0.15	grafana.k8s.lab prometheus.k8s.lab registry.k8s.lab znn.k8s.lab
```

Check the correct IP by running:

```
$ kubectl -n ingress-nginx get svc ingress-nginx-controller
NAME                                 TYPE           CLUSTER-IP      EXTERNAL-IP   PORT(S)                      AGE
ingress-nginx-controller             LoadBalancer   172.17.22.61    10.0.0.15     80:32521/TCP,443:31004/TCP   2d4h
```

# Kube ZNN

Kube ZNN is used to act as the target system for the tests. The images are built and pushed by the `kube-znn/build.sh` script. This script tags the images with the `registry.k8s.lab` prefix, it is the Ingress for the registry installed by `helmfile.yaml`.

The deploy for Kube ZNN is made via kustomize in the `kube/manifests` directory. It is recommended to use:

```bash
kubectl apply -k kube-znn/manifests/overlay/800k/
```

# Test Running

The tests are executed by Locust (https://locust.io/). Install it by using Python venv:

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
```

There's a complete suite of tests on `run_tests.sh`. This script will configure the cluster with various configurations and run the same locust script in headless mode to generate load. The results are saved on CSV files under `tests/results`.

# CSA Java incremental validation status (point 5)

The migration from `autoscalers/csa` (Python) to `autoscalers/csa-java` was validated incrementally in the cluster with focused scenarios before integrating `csa-java` into the full test pipeline.

## How the tests were executed

Environment:
- `KUBECONFIG=$PWD/vagrant-kubeadm-kubernetes/configs/config`
- Load generator: `.venv/bin/locust`
- App baseline: `kube-znn/manifests/overlay/800k`

Per scenario, the same flow was used:

```bash
kubectl delete csa csa-znn -n default --ignore-not-found
kubectl delete hpa znn -n default --ignore-not-found
kubectl delete -k kube-znn/manifests/overlay/800k/ --ignore-not-found
kubectl apply -k kube-znn/manifests/overlay/800k/
kubectl apply -f <custom-selfadapter-manifest>
PROM_EXTRACT_NAME=<scenario_name> .venv/bin/locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
```

Scenarios executed so far:
- Java H: `autoscalers/csa-java/custom-selfadapter-h.yaml` -> `tests/results/val5_java_h.csv`
- Python H: `autoscalers/csa/custom-selfadapter-h.yaml` -> `tests/results/val5_py_h.csv`
- Java VQ: `autoscalers/csa-java/custom-selfadapter-vq.yaml` -> `tests/results/val5_java_vq.csv`
- Python VQ: `autoscalers/csa/custom-selfadapter-vq.yaml` -> `tests/results/val5_py_vq.csv`
- Java H with increased command timeout (`5000ms`): `tests/results/val5_java_h_t5.csv`
- Java VQ with increased command timeout (`5000ms`): `tests/results/val5_java_vq_t5.csv`

## Current results (intermediate)

| Scenario | Requests | Fail ratio | Avg (ms) | P95 (ms) | Max pods/tag | Tags seen | CPU limit range |
|---|---:|---:|---:|---:|---:|---|---|
| `val5_java_h` | 6268 | 0.0000 | 3229.1 | 5918.9 | 1 | 800k | 0.15-0.15 |
| `val5_py_h` | 12705 | 0.0726 | 1603.3 | 4457.4 | 3 | 800k | 0.15-0.15 |
| `val5_java_vq` | 7130 | 0.0000 | 2842.4 | 5541.7 | 1 | 800k | 0.15-0.15 |
| `val5_py_vq` | 7974 | 0.1046 | 2088.6 | 4818.7 | 1 | 800k | 0.15-0.15 |
| `val5_java_h_t5` | 7101 | 0.1148 | 2850.7 | 5719.6 | 1 | 800k | 0.15-0.15 |
| `val5_java_vq_t5` | 6915 | 0.1118 | 2922.7 | 6046.4 | 1 | 800k | 0.15-0.15 |

Observations from controller logs during these runs:
- With Java timeout at `2000ms`, `evaluate` frequently timed out in cluster load.
- Increasing Java timeout to `5000ms` reduced the `evaluate` timeout issue, but did not fully stabilize all cycles under peak load.
- Java H still produced repeated adaptation errors on `adapt_replicas` calls.
- Java VQ continued without effective CPU/tag adaptation (tag remained `800k`, CPU limit remained `0.15`).
- Python H showed effective replica adaptation behavior during validation runs.

This is an intermediate checkpoint for point 5. Next step is to fix Java adaptation error paths (not only timeout), then rerun the same scenarios and proceed to pipeline integration.

# Result Visualization

Some results can be visualized by running the `plot_*` scripts.

- `plot_graphs.py` shows or saves a PNG file containing the detailed data collected by one locust run.
- `plot_comparison.py` shows a comparison between multiple configurations, configured by a JSON file like `comparison_p95_hpa_csa.json`.
- `plot_comparison_aggregated.py` aggregates multiple script runs and show a comparison between each configuration, aggregated by the runs.
