#!/bin/bash
#
# Usage: ./run_tests_by_cenary.sh [base,hpa,csa,csa-java,csa-go,vpa|all] [iterations]
# Defaults to all scenarios and 50 iterations. A trailing comma is accepted.
# Runs the selected test groups, consisting in the following:
# - kube-znn:800k
#   - 1 replica
#   - 5 replicas
# - hpa
#   - kube-znn:800k + hpa standard
#   - kube-znn:800k + hpa behavior scaledown stabilizationWindowSeconds=10
# - csa horizontal
#   - kube-znn:800k
# - csa horizontal + tag quality
#   - kube-znn:800k rollingUpdate maxSurge=25% & maxUnavailable=25%
#   - kube-znn:800k rollingUpdate maxSurge=50% & maxUnavailable=50%
# - csa-java horizontal
#   - kube-znn:800k
# - csa-java horizontal + tag quality
#   - kube-znn:800k rollingUpdate maxSurge=25% & maxUnavailable=25%
#   - kube-znn:800k rollingUpdate maxSurge=50% & maxUnavailable=50%
# - csa-go horizontal
#   - kube-znn:800k
# - csa-go horizontal + tag quality
#   - kube-znn:800k rollingUpdate maxSurge=25% & maxUnavailable=25%
#   - kube-znn:800k rollingUpdate maxSurge=50% & maxUnavailable=50%
# - vpa
# - csa vertical + tag quality
# - csa-java vertical + tag quality
# - csa-go vertical + tag quality
# sleeps for a minute between locust executions

wait_for_kube_znn_pods_deleted() {
    local attempts

    attempts=0

    while kubectl get pod -l app=kube-znn -o name 2>/dev/null | grep -q .; do
        attempts=$((attempts + 1))

        if [ "$attempts" -ge 90 ]; then
            echo "Timed out waiting for kube-znn pods to be deleted" >&2
            kubectl get pod -l app=kube-znn >&2 || true
            return 1
        fi

        sleep 2
    done
}

get_expected_kube_znn_pod() {
    local -a kube_znn_pods

    kubectl rollout status deployment/kube-znn --timeout=180s > /dev/null|| return 1
    kubectl wait --for=condition=Ready pod -l app=kube-znn --timeout=180s > /dev/null || return 1

    mapfile -t kube_znn_pods < <(
        kubectl get pod -l app=kube-znn --field-selector=status.phase=Running \
            -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'
    )

    if [ "${#kube_znn_pods[@]}" -ne 1 ]; then
        echo "Expected exactly one running kube-znn pod, found ${#kube_znn_pods[@]}" >&2
        kubectl get pod -l app=kube-znn >&2 || true
        return 1
    fi

    printf '%s\n' "${kube_znn_pods[0]}"
}

scenario_selected() {
    [[ ",$2," == *",$1,"* || ",$2," == *,all,* ]]
}

run_test_suite() {
    local ITERATION scenario_list
    
    ITERATION=$1
    scenario_list=$2
    
    kubectl delete hpa znn
    kubectl delete csa csa-znn
    kubectl delete -f autoscalers/csa-go/custom-selfadapter-h.yaml --ignore-not-found=true
    kubectl delete vpa znn
    kubectl delete -k kube-znn/manifests/overlay/800k/
    
    if scenario_selected base "$scenario_list"; then
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl scale deployment kube-znn --replicas 1
        PROM_EXTRACT_NAME=${ITERATION}_0_base_1 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        sleep 60

        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl scale deployment kube-znn --replicas 5
        sleep 10
        PROM_EXTRACT_NAME=${ITERATION}_0_base_5 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl scale deployment kube-znn --replicas 1
        sleep 60
    fi

    if scenario_selected hpa "$scenario_list"; then
        echo "####################################"
        echo "#           Starting HPA           #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/hpa/znn.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_1_hpa_std locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/hpa/znn.yaml
        sleep 60

        echo "####################################"
        echo "#        Starting HPA Fast         #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/hpa/znn_fast.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_2_hpa_fast locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/hpa/znn_fast.yaml
        sleep 60
    fi

    if scenario_selected csa "$scenario_list"; then
        echo "####################################"
        echo "#          Starting CSA H          #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa/custom-selfadapter-h.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_h locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa/custom-selfadapter-h.yaml
        sleep 60

        echo "####################################"
        echo "#       Starting CSA HQ 25%        #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl patch deployment kube-znn --type=merge -p '{"spec":{"strategy":{"rollingUpdate":{"maxUnavailable":"25%","maxSurge":"25%"}}}}'
        kubectl apply -f autoscalers/csa/custom-selfadapter-hq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_hq_25 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa/custom-selfadapter-hq.yaml
        sleep 60

        echo "####################################"
        echo "#       Starting CSA HQ 50%        #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl patch deployment kube-znn --type=merge -p '{"spec":{"strategy":{"rollingUpdate":{"maxUnavailable":"50%","maxSurge":"50%"}}}}'
        kubectl apply -f autoscalers/csa/custom-selfadapter-hq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_hq_50 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa/custom-selfadapter-hq.yaml
        sleep 60
    fi

    if scenario_selected csa-java "$scenario_list"; then
        echo "####################################"
        echo "#       Starting CSA Java H        #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa-java/custom-selfadapter-h.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_java_h locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        sleep 60
        kubectl delete -f autoscalers/csa-java/custom-selfadapter-h.yaml

        echo "####################################"
        echo "#    Starting CSA Java HQ 25%      #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl scale deployment kube-znn --replicas 1
        kubectl patch deployment kube-znn --type=merge -p '{"spec":{"strategy":{"rollingUpdate":{"maxUnavailable":"25%","maxSurge":"25%"}}}}'
        kubectl apply -f autoscalers/csa-java/custom-selfadapter-hq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_java_hq_25 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-java/custom-selfadapter-hq.yaml
        sleep 60

        echo "####################################"
        echo "#    Starting CSA Java HQ 50%      #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl patch deployment kube-znn --type=merge -p '{"spec":{"strategy":{"rollingUpdate":{"maxUnavailable":"50%","maxSurge":"50%"}}}}'
        kubectl apply -f autoscalers/csa-java/custom-selfadapter-hq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_java_hq_50 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-java/custom-selfadapter-hq.yaml
        sleep 60
    fi

    if scenario_selected base "$scenario_list"; then
        echo "####################################"
        echo "#      Starting Base CPU 1500m     #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        wait_for_kube_znn_pods_deleted || return 1
        kubectl apply -k kube-znn/manifests/overlay/800k/ || return 1
        PATCH_CPU='{ "spec": { "containers": [ { "name": "znn", "resources": { "limits": { "cpu": "750m" } } }, { "name": "nginx", "resources": { "limits": { "cpu": "750m" } } } ] } }'
        KUBE_ZNN_POD=$(get_expected_kube_znn_pod) || return 1
        echo Will patch $KUBE_ZNN_POD
        kubectl patch pod "$KUBE_ZNN_POD" --subresource resize --patch "$PATCH_CPU" || return 1
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_4_base_1500 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        sleep 60
    fi

    if scenario_selected vpa "$scenario_list"; then
        echo "####################################"
        echo "#           Starting VPA           #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/vpa/znn.yaml
        sleep 30
        PROM_EXTRACT_NAME=${ITERATION}_5_vpa locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/vpa/znn.yaml
        sleep 60
    fi

    if scenario_selected csa "$scenario_list"; then
        echo "####################################"
        echo "#          Starting CSA V          #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa/custom-selfadapter-v.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_6_csa_v locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa/custom-selfadapter-v.yaml
        sleep 60

        echo "####################################"
        echo "#         Starting CSA V+Q         #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa/custom-selfadapter-vq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_6_csa_vq locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa/custom-selfadapter-vq.yaml
        sleep 60
    fi

    if scenario_selected csa-java "$scenario_list"; then
        echo "####################################"
        echo "#       Starting CSA Java V        #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa-java/custom-selfadapter-v.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_6_csa_java_v locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-java/custom-selfadapter-v.yaml
        sleep 60

        echo "####################################"
        echo "#      Starting CSA Java V+Q       #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa-java/custom-selfadapter-vq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_6_csa_java_vq locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-java/custom-selfadapter-vq.yaml
        sleep 60
    fi

    if scenario_selected csa-go "$scenario_list"; then
        echo "####################################"
        echo "#        Starting CSA Go H         #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa-go/custom-selfadapter-h.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_go_h locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-go/custom-selfadapter-h.yaml --ignore-not-found=true
        sleep 60

        echo "####################################"
        echo "#      Starting CSA Go HQ 25%      #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl patch deployment kube-znn --type=merge -p '{"spec":{"strategy":{"rollingUpdate":{"maxUnavailable":"25%","maxSurge":"25%"}}}}'
        kubectl apply -f autoscalers/csa-go/custom-selfadapter-hq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_go_hq_25 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-go/custom-selfadapter-hq.yaml --ignore-not-found=true
        sleep 60

        echo "####################################"
        echo "#      Starting CSA Go HQ 50%      #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl patch deployment kube-znn --type=merge -p '{"spec":{"strategy":{"rollingUpdate":{"maxUnavailable":"50%","maxSurge":"50%"}}}}'
        kubectl apply -f autoscalers/csa-go/custom-selfadapter-hq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_3_csa_go_hq_50 locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-go/custom-selfadapter-hq.yaml --ignore-not-found=true
        sleep 60

        echo "####################################"
        echo "#        Starting CSA Go V         #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa-go/custom-selfadapter-v.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_6_csa_go_v locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-go/custom-selfadapter-v.yaml --ignore-not-found=true
        sleep 60

        echo "####################################"
        echo "#       Starting CSA Go V+Q        #"
        echo "####################################"

        kubectl delete -k kube-znn/manifests/overlay/800k/
        kubectl apply -k kube-znn/manifests/overlay/800k/
        kubectl apply -f autoscalers/csa-go/custom-selfadapter-vq.yaml
        sleep 5
        PROM_EXTRACT_NAME=${ITERATION}_6_csa_go_vq locust --headless --only-summary --processes 4 -H https://znn.k8s.lab -f tests/scenarios/locustfile.py
        kubectl delete -f autoscalers/csa-go/custom-selfadapter-vq.yaml --ignore-not-found=true
        sleep 60
    fi
}

main() {
    local iteration iteration_number scenario scenario_list=${1:-all} total=${2:-50}
    local -a scenarios

    if [ "$#" -gt 2 ]; then
        echo "Usage: $0 [base,hpa,csa,csa-java,csa-go,vpa|all] [iterations]" >&2
        return 1
    fi

    if [ "$#" -eq 1 ] && [[ "$1" =~ ^[0-9]+$ ]]; then
        total=$1
        scenario_list=all
    fi

    if ! [[ "$total" =~ ^[1-9][0-9]*$ ]]; then
        echo "Iterations must be a positive integer: $total" >&2
        return 1
    fi

    IFS=',' read -r -a scenarios <<< "$scenario_list"
    for scenario in "${scenarios[@]}"; do
        case "$scenario" in
            base|hpa|csa|csa-java|csa-go|vpa|all) ;;
            *)
                echo "Unknown scenario '$scenario'. Choose: base,hpa,csa,csa-java,csa-go,vpa,all" >&2
                return 1
                ;;
        esac
    done

    for iteration_number in $(seq 1 "$total"); do
        printf -v iteration '%02d' "$iteration_number"
        echo "#############################################"
        echo "#         Starting run ${iteration}         #"
        echo "#############################################"
        run_test_suite "$iteration" "$scenario_list"
        echo "Iteration ${iteration} ended at $(date)"
    done
    
}

main "$@"
