# Empacotamento e validação funcional Go

Data: 2026-09-24. Revisão inicial do laboratório: `3e0e43035a8766eafbbdf507bd25d1d238d3fa5d`.
Diretório dos comandos: `autoscalers/csa-go/`, salvo indicação diferente. Esta entrega
registrou a inspeção das imagens locais, publicação dos perfis CSA Go e validação controlada
no cluster provisionado. O conteúdo é evidência desta revisão; digests com tags mutáveis
podem mudar em publicações futuras.

## Imagens locais consultadas

O inventário confirmou quatro tags de cada implementação existente e revelou que todas usam
o runtime CSA Alpine em `registry.k8s.lab`. A imagem Go foi baseada nele, preservando o
`CMD ["/app/custom-self-adapter"]` do runtime, e acrescentou somente o executável e
`/config.yaml`. A base foi fixada pelo digest encontrado localmente.

Comandos executados:

```sh
docker images --digests --no-trunc --format '{{.Repository}}:{{.Tag}} {{.Digest}}' \
  | rg '^registry\.k8s\.lab/(csa-znn|csa-znn-java|custom-self-adapter):' | sort
docker image inspect registry.k8s.lab/custom-self-adapter:alpine-latest \
  --format 'ID={{.Id}} RepoDigests={{json .RepoDigests}} Cmd={{json .Config.Cmd}} OS={{.Os}} Arch={{.Architecture}}'
docker image inspect registry.k8s.lab/csa-znn:{h,hq,v,vq} \
  registry.k8s.lab/csa-znn-java:{h,hq,v,vq} --format '{{index .RepoTags 0}} {{.Id}} {{.Size}} {{json .RepoDigests}}'
curl -fsS http://registry.k8s.lab/v2/znn/tags/list | jq -c '{name,tags}'
docker version --format 'Docker client {{.Client.Version}}; server {{.Server.Version}}'
mise exec -- go version
kubectl version --client -o yaml
```

Versões observadas: Docker client/server 29.7.2; Go `go1.25.14 linux/amd64` via mise;
kubectl client v1.36.4. O cluster usa Kubernetes v1.35.3 nos quatro nós Ready. O registry
listou as tags Znn `100k`, `200k`, `20k`, `400k`, `600k` e `800k`.

| Tag | CSA Python digest | CSA Java digest | CSA Go digest publicado |
|---|---|---|---|
| h | `sha256:f62480de9f62a60f7c198decb2122b037dd3c823a3d796b6ca76cd04bc2e47bb` | `sha256:49718b950533b9ba274671665da47fce68445cc175fb2c179be1131ff46a8b7d` | `sha256:91f9ac6361fb5d7ed015786c96ad38bb289f1e6abf4274f21f077bb1afbbbd60` |
| hq | `sha256:8550bb657400033562628e96e7da12f6dd9c81d7a5938aa4dc2ac2dd3c2abdf8` | `sha256:7ba9b1f1e77359da1093f2c323345891d7537ba4b2098c470b15719cb5becb9b` | `sha256:e34787658fd7446a0569d0901f71f7a1d482639611cadcd44c332a9466a12350` |
| v | `sha256:5dc7ed5ccc27ed80da1659cbba45408cc47214f638f2961d5e081fee1194db9e` | `sha256:c915a2cc0c9843765806a7c19ba756ed347c2ff83cd3e55e573515381bb07c49` | `sha256:6ca690d68a5d1b018c58b348d2939a0e5f907f02f6654b7c097be46ce715dba8` |
| vq | `sha256:cdf666f3e8adb0f4e30ed58e5549b6d4b0023e96556916e72699f6b8aaead977` | `sha256:1aab380f92934a6fea059c4913f93eef533ac8c3698272350069c2f9d787f33b` | `sha256:2f8df30f6ad8b02a37d9ed58b7c2897ac24877607d2f48ea7d24e4a6850de062` |

The pinned runtime base is `registry.k8s.lab/custom-self-adapter:alpine-latest@sha256:b10f5b6536fb08425c25c4143f39ba44732df984399471e0228b8dfe48095647` (local image ID `sha256:6c28100a79af215efe63eae9e514449137c58f38df4d5b86c15ebff46440df02`). The Go images occupy about 112.25 MB each in Docker's local image-size report, versus about 242.47 MB for the inspected Python h image and 306.80 MB for the Java h image. Those totals include different files and shared base layers and are not a performance comparison.

## Build e artefatos

`Dockerfile` compiles no código: `build_csa-go.sh` produz um binário `linux/amd64`, sem CGO,
com Go 1.25 via mise; Docker copia o binário estático e o perfil escolhido para a base
fixada. O script publica no registry e gera `custom-selfadapter-${TAG}.yaml`. A identificação
da imagem Go é separada (`registry.k8s.lab/csa-znn-go`) e os nomes do CR/contêiner também são
`csa-znn-go`.

Comandos executados:

```sh
bash -n build_csa-go.sh
TAG=h ./build_csa-go.sh
printf '%s' '{"resource":{"spec":{"replicas":1}},"kubernetesMetrics":[{"spec":{"external":{"target":{"value":"1000"}}},"external":{"current":{"value":"950"}}}]}' \
  | docker run --rm -i --entrypoint /app/csa-go registry.k8s.lab/csa-znn-go:h -m metric
for profile in hq v vq; do TAG="$profile" ./build_csa-go.sh; done
ruby -e 'require "yaml"; require "pathname"; files = Pathname("profiles").glob("*.yaml").to_a; files.each { |file| YAML.load_file(file) }; YAML.load_file("custom-selfadapter-template.yaml"); puts "valid yaml: #{files.length} profiles and manifest template"'
for profile in h hq v vq; do docker run --rm --entrypoint /bin/sh "registry.k8s.lab/csa-znn-go:$profile" -c 'test -x /app/csa-go && test -r /config.yaml'; done
if TAG=bad ./build_csa-go.sh; then echo 'invalid TAG unexpectedly succeeded' >&2; exit 1; else code=$?; test "$code" -eq 2; echo "invalid TAG rejected with exit $code"; fi
for manifest in custom-selfadapter-h.yaml custom-selfadapter-hq.yaml custom-selfadapter-v.yaml custom-selfadapter-vq.yaml; do kubectl apply --dry-run=server -f "$manifest" -o name; done
ruby -ryaml <<'RUBY'
expected = {"h" => ["adapt_replicas"], "hq" => ["adapt_replicas", "adapt_tag"], "v" => ["adapt_cpu"], "vq" => ["adapt_cpu", "adapt_tag"]}
expected.each do |tag, strategies|
  config = YAML.load_file("profiles/#{tag}.yaml")
  raise tag unless config.values_at("interval", "minReplicas", "maxReplicas", "maxCPU") == [5000, 1, 5, 750]
  raise tag unless config.dig("metric", "timeout") == 1000 && config.dig("evaluate", "timeout") == 2000
  raise tag unless config["enabled_strategies"] == strategies
  %w[adapt_replicas adapt_cpu adapt_tag].each do |mode|
    raise "#{tag}/#{mode}" unless config.dig("adapt", mode, "timeout") == 2000 && config.dig("adapt", mode, "shell", "entrypoint") == "/app/csa-go"
  end
  manifest = YAML.load_file("custom-selfadapter-#{tag}.yaml")
  raise "manifest/#{tag}" unless manifest.dig("spec", "template", "spec", "containers", 0, "image") == "registry.k8s.lab/csa-znn-go:#{tag}"
  raise "target/#{tag}" unless manifest.dig("spec", "scaleTargetRef", "name") == "kube-znn"
end
puts "4 profiles and 4 generated manifests match config/image contracts"
RUBY
```

O teste dentro da imagem h retornou `{"current_replicas":1,"target_value":"1000","current_value":"950"}`. Todas as imagens resultantes mantêm o CMD herdado do runtime. Cada imagem foi testada para confirmar `/app/csa-go` executável e `/config.yaml` legível. A validação Ruby leu os quatro perfis e quatro manifests gerados e confirmou interval 5000, limites de réplicas 1–5, `maxCPU` 750, alvo 1000, timeouts metric 1000 ms e evaluate/adaptações 2000 ms, estratégias esperadas e nomes de imagem. `TAG=bad ./build_csa-go.sh` foi rejeitado com status 2 antes do build. `kubectl apply --dry-run=server -f ...` validou cada CR contra o schema instalado sem gravar recursos.

A primeira tentativa de parser foi
`python3 -c 'import pathlib,yaml; files=list(pathlib.Path("profiles").glob("*.yaml")); [yaml.safe_load(p.read_text()) for p in files]; yaml.safe_load(pathlib.Path("custom-selfadapter-template.yaml").read_text()); print(f"valid yaml: {len(files)} profiles and manifest template")'`, que encerrou com
`ModuleNotFoundError: No module named yaml`; nenhum pacote foi instalado. A validação foi
feita com a biblioteca YAML da instalação Ruby e, em seguida, com os asserts de estrutura.

| Perfil | Imagem local (ID) | Digest publicado | Estratégias habilitadas |
|---|---|---|---|
| h | `sha256:6492668aa2a46f5fd4b4be76ed5065dd6c14b48ba346708e1b4d35015c3d2c7e` | `sha256:91f9ac6361fb5d7ed015786c96ad38bb289f1e6abf4274f21f077bb1afbbbd60` | `adapt_replicas` |
| hq | `sha256:c1c83bbe0cd54ddd6fc9dd761f69916f142b16616aa7681bbf5dd0de4c486030` | `sha256:e34787658fd7446a0569d0901f71f7a1d482639611cadcd44c332a9466a12350` | `adapt_replicas`, `adapt_tag` |
| v | `sha256:364c6882164e2f9b0c43ecf8d2913e09cf2cf14f424d73131a0fc25f044b73b9` | `sha256:6ca690d68a5d1b018c58b348d2939a0e5f907f02f6654b7c097be46ce715dba8` | `adapt_cpu` |
| vq | `sha256:3f71b980d0c6db798d300a74c000bc2740f90051e9f9c8045383d598ce5a1e4f` | `sha256:2f8df30f6ad8b02a37d9ed58b7c2897ac24877607d2f48ea7d24e4a6850de062` | `adapt_cpu`, `adapt_tag` |

Os manifests publicados usam o namespace `default`, o Deployment `kube-znn`, o seletor
`autoscaling.lab/adaptation: "yes"`, requests `128m`/`128Mi`, limits `1024m`/`1024Mi`,
`imagePullPolicy: Always` e permissão adicional `patch` sobre `pods/resize`, conforme os
manifests Python/Java.

## Estado observado antes do teste

```sh
kubectl config current-context
kubectl cluster-info
kubectl get nodes -L autoscaling.lab/adaptation -o wide
kubectl get customselfadapters.custom-self-adapter.net -A -o name
kubectl get namespace csa-go-smoke --ignore-not-found -o name
kubectl -n default get pod -l app=kube-znn -o wide
kubectl -n default get deployment kube-znn -o json | jq '{generation:.metadata.generation, replicas:.spec.replicas, ready:.status.readyReplicas, containers:[.spec.template.spec.containers[]|{name,image,cpu:.resources.limits.cpu,memory:.resources.limits.memory}]}'
```

Contexto: `kubernetes-admin@kubernetes`; API server `https://10.0.0.10:6443`. O node `node02`
é o único com `autoscaling.lab/adaptation=yes`. Não havia CR CSA ativo nem namespace
`csa-go-smoke`. O Deployment de referência `default/kube-znn` estava na geração 13, com
uma réplica pronta, `znn:400k` e nginx, ambos com limite `750m`/`64Mi`.

Antes da criação foram consultados `kubectl get namespace csa-go-smoke --ignore-not-found -o name`
e `kubectl get customselfadapters.custom-self-adapter.net -A -o name`; ambos não retornaram
recursos. O Pod `default/kube-znn` estava em `node01`. Os documentos exatos aplicados foram:

```sh
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: Namespace
metadata:
  name: csa-go-smoke
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: csa-go-target
  namespace: csa-go-smoke
  labels:
    app: csa-go-target
spec:
  replicas: 1
  selector:
    matchLabels:
      app: csa-go-target
  template:
    metadata:
      labels:
        app: csa-go-target
    spec:
      nodeSelector:
        kubernetes.io/hostname: node01
      containers:
        - name: znn
          image: registry.k8s.lab/znn:400k
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 9000
          resources:
            requests:
              cpu: 50m
              memory: 16Mi
            limits:
              cpu: 500m
              memory: 64Mi
        - name: nginx
          image: openresty/openresty:alpine-fat
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 80
            - containerPort: 9145
          resources:
            requests:
              cpu: 50m
              memory: 16Mi
            limits:
              cpu: 500m
              memory: 64Mi
EOF
kubectl -n csa-go-smoke rollout status deployment/csa-go-target --timeout=120s
kubectl -n csa-go-smoke get deployment,pods -o wide

kubectl apply -f - <<'EOF'
apiVersion: custom-self-adapter.net/v1
kind: CustomSelfAdapter
metadata:
  namespace: csa-go-smoke
  name: csa-go-smoke
spec:
  template:
    spec:
      nodeSelector:
        autoscaling.lab/adaptation: "yes"
      containers:
        - name: csa-znn-go
          image: registry.k8s.lab/csa-znn-go:vq
          imagePullPolicy: Always
          resources:
            requests:
              cpu: 128m
              memory: 128Mi
            limits:
              cpu: 1024m
              memory: 1024Mi
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: csa-go-target
  roleRequiresMetricsServer: true
  additionalRoleRules:
    - apiGroups: [""]
      resources:
        - pods/resize
      verbs:
        - patch
EOF
kubectl -n csa-go-smoke wait --for=condition=Ready pod/csa-go-smoke --timeout=120s
kubectl -n csa-go-smoke get pod csa-go-smoke -o wide
kubectl -n csa-go-smoke logs csa-go-smoke --tail=100
```

## Validação controlada

O teste criou o namespace descartável `csa-go-smoke`, um Deployment `csa-go-target` com uma
réplica e limites iniciais de CPU `500m`, e um CR `csa-go-smoke` apontando para esse alvo e
para `registry.k8s.lab/csa-znn-go:vq`. O alvo usou as imagens já presentes no cluster
(`registry.k8s.lab/znn:400k` e `openresty/openresty:alpine-fat`) e foi fixado em `node01`.
O CR preservou o node selector, requests/limits, metrics-server requirement e a regra de
`pods/resize`. O operador criou o Pod CSA `csa-go-smoke` em `node02`, com a ServiceAccount
`csa-go-smoke`; a imagem em execução informou o digest vq publicado acima. O Pod ficou Ready
e os logs confirmaram a inicialização do runtime e do ciclo do autoscaler.

O Deployment foi criado com os dois containers usados pelas adaptações, `znn` em
`registry.k8s.lab/znn:400k` e `nginx` em `openresty/openresty:alpine-fat`; ambos tinham
requests `50m`/`16Mi`, limites `500m`/`64Mi` e a réplica ficou em `node01`. O CR de teste usou
`registry.k8s.lab/csa-znn-go:vq`, ServiceAccount gerenciada pelo operador, node selector
`autoscaling.lab/adaptation: "yes"`, requests `128m`/`128Mi`, limits `1024m`/`1024Mi`,
`roleRequiresMetricsServer: true` e `patch` em `pods/resize`. Os comandos aplicaram esses dois
documentos YAML via `kubectl apply -f -` depois de confirmar que namespace e CR não existiam.

Comandos de verificação e adaptação executados dentro do Pod provisionado pelo operador:

```sh
printf '%s' '{"resource":{"metadata":{"name":"csa-go-target","namespace":"csa-go-smoke"}},"evaluation":{"parameters":{"replicas":2}}}' \
  | kubectl exec -i -n csa-go-smoke csa-go-smoke -- /app/csa-go -m adapt_replicas
kubectl -n csa-go-smoke rollout status deployment/csa-go-target --timeout=120s

printf '%s' '{"resource":{"metadata":{"name":"csa-go-target","namespace":"csa-go-smoke"}},"evaluation":{"parameters":{"cpu_multiplier":1.2}}}' \
  | kubectl exec -i -n csa-go-smoke csa-go-smoke -- /app/csa-go -m adapt_cpu

printf '%s' '{"resource":{"metadata":{"name":"csa-go-target","namespace":"csa-go-smoke"}},"evaluation":{"parameters":{"tag_up":false,"update_cpu":true}}}' \
  | kubectl exec -i -n csa-go-smoke csa-go-smoke -- /app/csa-go -m adapt_tag
kubectl -n csa-go-smoke rollout status deployment/csa-go-target --timeout=180s

printf '%s' '{"resource":{"metadata":{"name":"csa-go-target","namespace":"csa-go-smoke"}},"evaluation":{"parameters":{"tag_up":true}}}' \
  | kubectl exec -i -n csa-go-smoke csa-go-smoke -- /app/csa-go -m adapt_tag
kubectl -n csa-go-smoke rollout status deployment/csa-go-target --timeout=180s

printf '%s' '{"resource":{"metadata":{"name":"csa-go-target","namespace":"csa-go-smoke"},"spec":{"replicas":2}},"metrics":[{"value":"{\"current_value\":\"1200\",\"target_value\":\"1000\"}"}]}' \
  | kubectl exec -i -n csa-go-smoke csa-go-smoke -- /app/csa-go -m evaluate
printf '%s' '{"resource":{"metadata":{"name":"csa-go-target","namespace":"csa-go-smoke"},"spec":{"replicas":2}},"metrics":[{"value":"{\"current_value\":\"1200000\",\"target_value\":\"1000\"}"}]}' \
  | kubectl exec -i -n csa-go-smoke csa-go-smoke -- /app/csa-go -m evaluate

kubectl -n csa-go-smoke get deployment csa-go-target -o json | jq '{replicas:.spec.replicas,ready:.status.readyReplicas,containers:[.spec.template.spec.containers[]|{name,image,cpu:.resources.limits.cpu}]}'
kubectl -n csa-go-smoke get customselfadapters.custom-self-adapter.net csa-go-smoke -o json | jq '{initialData:.status.initialData,target:.spec.scaleTargetRef}'
kubectl -n csa-go-smoke get pods -l app=csa-go-target -o json | jq '[.items[]|{name:.metadata.name,containers:[.spec.containers[]|{name,cpu:.resources.limits.cpu}]}]'
kubectl -n csa-go-smoke get pod csa-go-smoke -o json | jq '{annotations:.metadata.annotations,env:[.spec.containers[0].env[]|select(.name=="CSA_NAME" or .name=="CSA_NAMESPACE" or .name=="scaleTargetRef" or .name=="namespace")|{name,value}]}'
```

Resultados: `adapt_replicas` mudou 1→2 e as duas réplicas ficaram Ready; `adapt_cpu` retornou
600 e redimensionou `znn` e nginx nos dois Pods para `600m`; o operador copiou a anotação para
`status.initialData` como `{"cpu_limit":500}`. `adapt_tag` reduziu a imagem de `400k` para
`200k`; com `update_cpu:true`, sincronizou `600m` no template do Deployment. A chamada com
`tag_up:true` retornou `400k` e concluiu o rollout. O estado reconciliado ficou
`{"cpu_limit":500,"tag":"400k"}`. A chamada direta a `evaluate`, com razão 1.2, retornou
`{"strategy":"adapt_cpu","parameters":{"cpu_multiplier":1.2}}`.

O ciclo automático iniciou e consultou o Deployment, mas o Metrics API não retornou
`znn_latency_ms_p95` para o namespace sem carga. O runtime reportou essa ausência em seus
logs e não iniciou uma adaptação automática. A etapa `evaluate` foi exercitada diretamente
com payload de métrica; nenhuma carga ou Locust foi executado.

O namespace inteiro foi removido com `kubectl delete namespace csa-go-smoke --wait=true
--timeout=120s` após as verificações. A consulta final confirmou que
`default/kube-znn` continuou na geração 13, com 1 réplica pronta, tag `400k` e limites
`750m`/`64Mi`; não restaram namespace, CR ou Pods do teste. Nenhum recurso de outro
experimento foi modificado. As verificações finais executadas foram:

```sh
kubectl get namespace csa-go-smoke --ignore-not-found -o name
kubectl get customselfadapters.custom-self-adapter.net -A -o name
kubectl -n default get deployment kube-znn -o json | jq '{generation:.metadata.generation, replicas:.spec.replicas, ready:.status.readyReplicas, containers:[.spec.template.spec.containers[]|{name,image,cpu:.resources.limits.cpu,memory:.resources.limits.memory}]}'
kubectl get pods -A | rg 'csa-go-smoke|csa-go-target' || true
```

## Verificações finais

```sh
mise exec -- go test ./... -count=1
mise exec -- go vet ./...
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 mise exec -- go build -trimpath -buildvcs=false -o /tmp/csa-go-final ./cmd/csa-go
bash -n build_csa-go.sh
git -C ../.. diff --check
git -C ../.. diff --exit-code -- autoscalers/csa autoscalers/csa-java
```

Nenhum teste unitário das referências foi adicionado ou executado. Nenhum script `run_tests*`,
Locust, cenário de carga ou matriz de performance foi executado. A integração desses scripts
permanece para a entrega 7.

Resultado: `go test`, `go vet`, build linux/amd64, `gofmt`, `bash -n`, verificação estrutural
dos perfis, dry-run server dos quatro CRs, diff checks e preservação das referências passaram.
A checagem documental final encontrou 8 arquivos Markdown, 106 links e 19 fixtures válidos,
com zero erros.

## Complemento solicitado — manifesto e alvo reais em `default`

Após a validação isolada, foi solicitado conferir a instalação no namespace e alvo usados pelos
experimentos. O contexto foi novamente confirmado como `kubernetes-admin@kubernetes`, não
havia CR ativo, e `external.metrics.k8s.io` retornou `items: []` para
`default/znn_latency_ms_p95`. O baseline do Deployment era geração 13, 1 réplica Ready,
`znn:400k`, nginx, limites `750m`/`64Mi` nos dois containers.

```sh
kubectl config current-context
kubectl cluster-info
kubectl get customselfadapters.custom-self-adapter.net -A -o json | jq '[.items[]|{namespace:.metadata.namespace,name:.metadata.name,target:.spec.scaleTargetRef}]'
kubectl -n default get deployment kube-znn -o json | jq '{generation:.metadata.generation,resourceVersion:.metadata.resourceVersion,replicas:.spec.replicas,ready:.status.readyReplicas,selector:.spec.selector.matchLabels,containers:[.spec.template.spec.containers[]|{name,image,cpu:.resources.limits.cpu,memory:.resources.limits.memory}]}'
kubectl -n default get pods -l app=kube-znn -o wide
kubectl get --raw '/apis/external.metrics.k8s.io/v1beta1/namespaces/default/znn_latency_ms_p95' | jq .
kubectl get verticalpodautoscalers.autoscaling.k8s.io -A -o json | jq '[.items[]|{namespace:.metadata.namespace,name:.metadata.name,targetRef:.spec.targetRef,updatePolicy:.spec.updatePolicy,resourcePolicy:.spec.resourcePolicy,recommendation:.status.recommendation}]'
```

Foram aplicados em `default` os manifests versionados. Primeiro, o h criou o CR
`default/csa-znn-go` e o Pod do operador com a imagem/digest h, em `node02`, usando a
ServiceAccount `csa-znn-go` e `CSA_NAMESPACE=default`. A avaliação real em carga de entrada
1.2 retornou `adapt_replicas` com 2. O modo de réplicas levou `kube-znn` de 1 para 2 (duas
réplicas Ready) e depois de volta a 1.

```sh
kubectl apply -f custom-selfadapter-h.yaml
kubectl -n default wait --for=condition=Ready pod/csa-znn-go --timeout=120s
kubectl -n default get customselfadapters.custom-self-adapter.net csa-znn-go -o json | jq '{namespace:.metadata.namespace,name:.metadata.name,target:.spec.scaleTargetRef,image:.spec.template.spec.containers[0].image}'
kubectl -n default get pod csa-znn-go -o json | jq '{node:.spec.nodeName,serviceAccount:.spec.serviceAccountName,image:.spec.containers[0].image,imageID:.status.containerStatuses[0].imageID,ready:.status.containerStatuses[0].ready,identity:[.spec.containers[0].env[]|select(.name=="CSA_NAME" or .name=="CSA_NAMESPACE")|{name,value}]}'

printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":1}},"metrics":[{"value":"{\"current_value\":\"1200000\",\"target_value\":\"1000\"}"}]}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m evaluate
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"replicas":2}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_replicas
kubectl -n default rollout status deployment/kube-znn --timeout=120s
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"replicas":1}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_replicas
kubectl -n default rollout status deployment/kube-znn --timeout=120s
```

Em seguida, o mesmo CR foi atualizado pelo manifesto vq, recriando o runtime com o digest vq.
A avaliação com razão 1.2 retornou `adapt_tag`, `tag_up:false` e `update_cpu:true`. CPU usou
o estado `initialData` 750 e chamou `pods/resize` nos containers reais. O log mostrou cálculo
180 para entrada 1.2 antes de aplicar o limite 750; isso corresponde a 150m como CPU efetiva
lida do Pod antes do resize. A consulta inicial havia salvo o CPU do Deployment, 750m, mas
não havia registrado os limites do Pod antes da primeira mutação. Sem VPA ativo, a chamada
redimensionou o Pod para 750m em ambos os containers, igual ao Deployment. A tag mudou para
200k e voltou a 400k, com rollouts Ready; `status.initialData` ficou
`{"cpu_limit":750,"tag":"400k"}`.

```sh
kubectl apply -f custom-selfadapter-vq.yaml
kubectl -n default get pod csa-znn-go -o json | jq '{node:.spec.nodeName,image:.spec.containers[0].image,imageID:.status.containerStatuses[0].imageID,ready:.status.containerStatuses[0].ready}'
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"cpu_multiplier":1.2}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_cpu
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":1}},"metrics":[{"value":"{\"current_value\":\"1200000\",\"target_value\":\"1000\"}"}]}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m evaluate
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"tag_up":false,"update_cpu":true}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_tag
kubectl -n default rollout status deployment/kube-znn --timeout=180s
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"tag_up":true}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_tag
kubectl -n default rollout status deployment/kube-znn --timeout=180s
```

O runtime automático iniciou, mas não executou avaliação/adaptação automática porque a métrica
externa estava vazia. Portanto, os modos Go foram chamados diretamente via `kubectl exec`, com
o Pod, configuração, ServiceAccount, namespace, Deployment e permissões reais; a geração da
métrica automática continua dependente de carga. O teste não executou carga nem Locust.

O CR Go foi removido e o Pod/ServiceAccount/Role/RoleBinding gerenciados desapareceram. O
Deployment final está em geração 17, com uma réplica Ready, imagem `znn:400k` e os limites
`750m`/`64Mi` em `znn` e nginx, iguais à especificação inicial. A réplica substituta está
Ready e o CR Go não existe mais. A geração e identidade do Pod mudaram por causa dos rollouts
controlados; a especificação da aplicação voltou ao baseline. O estado efetivo do Pod anterior
à primeira chamada de CPU não foi capturado, portanto não se afirma que seu resize transitório
de 150m foi reproduzido após o teste.

## Complemento — validação do perfil hq em `default`

O perfil `hq` ficou de fora da validação anterior por ter sido tratado como redundante aos
modos de réplicas (`h`) e tag (`vq`). Essa suposição não cobria a combinação habilitada
`adapt_replicas` + `adapt_tag`, nem a prioridade/fallback entre as duas estratégias. Para fechar
essa lacuna, o manifesto hq foi aplicado ao mesmo alvo real `default/kube-znn`. O CR e o Pod
`default/csa-znn-go` ficaram Ready em `node02`; a imagem declarada foi
`registry.k8s.lab/csa-znn-go:hq`.

Com a razão 1.2 e uma réplica, `evaluate` escolheu `adapt_replicas` com duas réplicas. A
adaptação levou o Deployment a duas réplicas Ready e, em seguida, a uma réplica Ready quando
restaurada. Para exercitar o fallback, `evaluate` recebeu cinco réplicas com a mesma razão
1.2 e escolheu `adapt_tag` com `tag_up:false` e `update_cpu:false`. Com uma réplica e razão
0.8, escolheu `adapt_tag` com `tag_up:true`. A chamada de tag reduziu `400k` para `200k`; a
primeira tentativa de retorno encontrou um conflito transitório de `resourceVersion` e
retornou `{"result":"error"}`. A repetição leu o estado atual e concluiu `200k` para `400k`.
Os rollouts terminaram Ready e o status armazenou `{"cpu_limit":750,"tag":"400k"}`.

Comandos executados:

```sh
kubectl config current-context
kubectl get customselfadapters.custom-self-adapter.net -A -o json | jq '[.items[]|{namespace:.metadata.namespace,name:.metadata.name,target:.spec.scaleTargetRef}]'
kubectl -n default get deployment kube-znn -o json | jq '{replicas:.spec.replicas,ready:.status.readyReplicas,containers:[.spec.template.spec.containers[]|{name,image,cpu:.resources.limits.cpu,memory:.resources.limits.memory}]}'
kubectl get --raw '/apis/external.metrics.k8s.io/v1beta1/namespaces/default/znn_latency_ms_p95' | jq .

kubectl apply -f custom-selfadapter-hq.yaml
kubectl -n default wait --for=condition=Ready pod/csa-znn-go --timeout=120s
kubectl -n default get customselfadapter csa-znn-go -o yaml
kubectl -n default get pod csa-znn-go -o wide

printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":1}},"metrics":[{"value":"{\"current_value\":\"1200000\",\"target_value\":\"1000\"}"}]}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m evaluate
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"replicas":2}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_replicas
kubectl -n default rollout status deployment/kube-znn --timeout=180s
kubectl -n default get deployment kube-znn -o json | jq '{replicas:.spec.replicas,ready:.status.readyReplicas}'
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"replicas":1}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_replicas
kubectl -n default rollout status deployment/kube-znn --timeout=180s

printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":5}},"metrics":[{"value":"{\"current_value\":\"1200000\",\"target_value\":\"1000\"}"}]}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m evaluate
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"},"spec":{"replicas":1}},"metrics":[{"value":"{\"current_value\":\"800000\",\"target_value\":\"1000\"}"}]}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m evaluate
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"tag_up":false,"update_cpu":false}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_tag
kubectl -n default rollout status deployment/kube-znn --timeout=180s
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"tag_up":true,"update_cpu":false}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_tag
# A primeira tentativa retornou result:error por conflito de resourceVersion.
printf '%s' '{"resource":{"metadata":{"name":"kube-znn","namespace":"default"}},"evaluation":{"parameters":{"tag_up":true,"update_cpu":false}}}' | kubectl exec -i -n default csa-znn-go -- /app/csa-go -m adapt_tag
kubectl -n default rollout status deployment/kube-znn --timeout=180s
kubectl -n default get deployment kube-znn -o json | jq '{replicas:.spec.replicas,ready:.status.readyReplicas,containers:[.spec.template.spec.containers[]|{name,image,cpu:.resources.limits.cpu,memory:.resources.limits.memory}]}'
kubectl -n default get customselfadapter csa-znn-go -o json | jq '.status.initialData'

kubectl -n default delete -f custom-selfadapter-hq.yaml --wait=true --timeout=120s
kubectl -n default wait --for=delete pod/csa-znn-go --timeout=120s
kubectl -n default rollout status deployment/kube-znn --timeout=180s
kubectl -n default get deployment kube-znn -o json | jq '{generation:.metadata.generation,replicas:.spec.replicas,ready:.status.readyReplicas,containers:[.spec.template.spec.containers[]|{name,image,cpu:.resources.limits.cpu,memory:.resources.limits.memory}]}'
kubectl get --raw '/apis/external.metrics.k8s.io/v1beta1/namespaces/default/znn_latency_ms_p95' | jq '.items | length'
kubectl get customselfadapters.custom-self-adapter.net -A -o name
kubectl -n default get pods,serviceaccounts,roles,rolebindings -o name | rg 'csa-znn-go' || true
```

O estado final ficou em geração 21, uma réplica Ready, imagem `znn:400k` e limites
`750m`/`64Mi` para `znn` e nginx; os requests permaneceram `50m`/`16Mi`. O CR, Pod e recursos
RBAC gerenciados do CSA Go foram removidos. A métrica externa continuava sem itens, então o
ciclo automático não executou a avaliação: os modos foram chamados diretamente dentro do Pod
provisionado pelo operador. Não houve teste de carga ou execução de Locust.
