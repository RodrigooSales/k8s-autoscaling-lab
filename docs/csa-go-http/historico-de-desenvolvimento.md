# CSA Go HTTP: histórico de desenvolvimento

## Preparação do ambiente

### Decisões

- A implementação fica isolada na branch `feat/csa-go-http` e no worktree `.worktrees/csa-go-http`, criado a partir de `dev`.
- O executável inicial fica em `autoscalers/csa-go-http/cmd/csa-go-http/`; esta pasta guarda o histórico próprio da implementação.
- O servidor HTTP usa `net/http` da biblioteca padrão. Não foi adicionada dependência externa para o esqueleto.
- O projeto seleciona Go 1.25 com `mise.toml`, seguindo o padrão do `csa-go` existente.
- O worktree reutiliza a `.venv` local e o ambiente Vagrant do checkout principal por links simbólicos, sem criar cópias ou inicializar os submódulos novamente. A `.venv` não é necessária para Go, mas permanece disponível conforme o fluxo acordado.
- O primeiro endpoint é `GET /health`, com resposta `{"status":"UP"}`. Ainda não há ciclo de coleta, avaliação ou adaptação.

### Comandos usados

Executados na raiz do repositório:

```sh
git worktree add -b feat/csa-go-http .worktrees/csa-go-http dev
ln -s /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/.venv .worktrees/csa-go-http/.venv
ln -s /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes .worktrees/csa-go-http/.vagrant-env
mkdir -p .worktrees/csa-go-http/autoscalers/csa-go-http .worktrees/csa-go-http/docs/csa-go-http
mise trust /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/.worktrees/csa-go-http/autoscalers/csa-go-http/mise.toml
mise exec -- gofmt -w cmd/csa-go-http/main.go
mise exec -- go version
```

Ambiente Go observado na raiz: Go 1.24.6. Dentro do módulo, o `mise.toml` selecionou Go 1.25.14, igual à linha 1.25 usada pelo `csa-go` existente.

### Validação

O esqueleto foi revisado estaticamente. Nenhum teste foi criado ou executado, nem cluster ou Vagrant foi iniciado nesta preparação.

## Atividade 1 — Coleta de métricas

### Decisões

- Para manter o código legível por etapa, `main.go` contém somente a inicialização HTTP e o health check. A configuração, o cliente e a coleta ficam em `cmd/csa-go-http/metrics.go`; os testes da coleta foram separados em `metrics_test.go`, e o teste do health check permanece em `main_test.go`.
- A coleta é uma operação interna para o ciclo futuro. Nenhuma rota de coleta foi adicionada; a aplicação continua expondo somente `GET /health`.
- O snapshot tipado guarda as réplicas atuais, a métrica atual em milésimos e o alvo inteiro configurado.
- A coleta lê primeiro `znn_latency_ms_p95` pela API de métricas externas e depois lê `spec.replicas` do Deployment. Soma o `value` de todos os itens e arredonda cada valor para cima ao converter para milésimos, preservando o contrato da implementação Java.
- A lista vazia da métrica retorna erro específico antes de consultar o Deployment. O seletor `labelSelector` só é enviado quando `CSA_METRIC_SELECTOR` tem conteúdo.
- Namespace, Deployment e métrica vêm de `CSA_NAMESPACE`, `CSA_DEPLOYMENT_NAME` e `CSA_METRIC_NAME`. `CSA_TARGET_VALUE` deve ser inteiro e assume `1000` quando vazio; `CSA_METRIC_SELECTOR` é opcional.
- O cliente dinâmico usa `k8s.io/client-go` e é criado uma vez, sob demanda na primeira coleta. O health check não carrega configuração nem credenciais Kubernetes. A coleta não consulta Pods nem CPU.
- As dependências diretas são `k8s.io/client-go v0.35.3` e `k8s.io/apimachinery v0.35.3`, alinhadas à versão Kubernetes 1.35.3 usada pelo laboratório.

### Comandos e validação

Executados na pasta `autoscalers/csa-go-http`:

```sh
mise exec -- go mod tidy
gofmt -w cmd/csa-go-http/main.go cmd/csa-go-http/main_test.go cmd/csa-go-http/metrics.go cmd/csa-go-http/metrics_test.go
mise exec -- go test ./cmd/csa-go-http
mise exec -- go test ./...
```

- RED inicial: `go test ./cmd/csa-go-http` não compilou porque o coletor e sua configuração ainda não existiam; este worktree expunha somente o health check antes da atividade.
- GREEN: `go test ./...` passou. Os testes usam `httptest.Server` e um cliente Kubernetes dinâmico real apontado ao servidor local; nenhum kubeconfig, cluster, Vagrant ou teste de carga foi usado.
- Checagem de regressão: a conversão foi temporariamente alterada de `Quantity.MilliValue()` para `Quantity.Value()`. O teste falhou como esperado: recebeu `1501` em vez de `1500001`. A conversão foi restaurada e `go test ./...` passou novamente.
- O sandbox bloqueou a abertura do listener local e o download de uma dependência transitiva do Go. A resolução de dependências e a suíte com listener foram concluídas após autorização para executar fora do sandbox.

## Atividade 2 — Avaliação

### Decisões

- A regra pura fica em `cmd/csa-go-http/evaluate.go`. Ela recebe um input tipado e não consulta Kubernetes, ambiente, arquivos ou rotas.
- O rate é `currentMetricValue / (targetMetricValue * 1000)`. Alvo zero retorna erro. As estratégias mantêm a ordem réplicas → CPU → tag nos dois sentidos de carga, com banda sem decisão entre `0.90` e `0.95`.
- Réplicas usam `ceil(currentReplicas * rate)`. Em carga alta, o resultado é limitado somente por `maxReplicas`; em carga baixa, somente por `minReplicas`, mantendo a regra direcional das implementações existentes.
- A decisão mantém o JSON `{ "strategy": ..., "parameters": ... }`. A tag em carga alta define `update_cpu` pela presença da estratégia `adapt_cpu` habilitada.
- Os dez casos `evaluate_*` do índice compartilhado são consumidos diretamente pelos testes. Casos adicionais cobrem alvo zero e limites direcionais de réplicas.

### Comandos e validação

Executados na pasta `autoscalers/csa-go-http`:

```sh
gofmt -w cmd/csa-go-http/evaluate.go cmd/csa-go-http/evaluate_test.go
mise exec -- go test ./cmd/csa-go-http -run '^TestEvaluate'
mise exec -- go test ./...
```

- RED inicial: os testes não compilaram porque `evaluate`, `evaluationInput` e `evaluationDecision` ainda não existiam.
- GREEN: os dez contratos e os testes adicionais passaram; `go test ./...` também passou, incluindo a regressão da coleta da Atividade 1.
- Checagem de regressão: o limiar foi temporariamente alterado de `>= 0.95` para `> 0.95`. `evaluate_rate_095_replica_priority` falhou com decisão nula em vez de adaptação de réplicas. O operador foi restaurado e os testes passaram novamente.
- A validação foi local. A avaliação não acessa Kubernetes; a suíte completa usa somente o `httptest.Server` local já criado para os testes de coleta. Nenhum cluster, kubeconfig, Vagrant ou teste de carga foi usado.

## Atividade 3 — Adaptação de réplicas

### Decisões

- Esta etapa implementa somente a adaptação de réplicas em `cmd/csa-go-http/adapt_replicas.go`. CPU, tag, armazenamento de estado inicial e ciclo de controle permanecem para atividades posteriores.
- `replicaAdapter.adapt()` envia um strategic merge patch com apenas `spec.replicas`. O avaliador já entrega a decisão tipada e limitada; o adaptador não relê o Deployment nem repete essa regra. Isso segue o serviço Java HTTP existente e evita outra chamada à API e o envio de campos que a adaptação não altera.
- O erro da API é propagado com o namespace e o nome do Deployment, deixando a camada HTTP futura decidir como responder. Nenhuma rota de adaptação foi adicionada.
- Os testes usam `httptest.Server` e um cliente dinâmico Kubernetes real apontado para o loopback. Assim verificam método, URL, content type, corpo do patch e propagação de falha sem cluster ou novas dependências.
- Durante a exploração, `dynamic/fake` exigiu dependências transitivas adicionais e não aplicou strategic merge patch ao objeto `Unstructured` do teste. O teste foi migrado para o padrão `httptest` já usado em `metrics_test.go`; `go.mod` e `go.sum` foram restaurados ao estado anterior à atividade.

### Comandos e validação

Executados na pasta `autoscalers/csa-go-http`:

```sh
mise exec -- go mod tidy
gofmt -w cmd/csa-go-http/adapt_replicas.go cmd/csa-go-http/adapt_replicas_test.go
mise exec -- go test ./cmd/csa-go-http -run '^TestReplicaAdapter'
mise exec -- go test ./cmd/csa-go-http -run '^TestReplicaAdapterPatchesOnlyDesiredReplicas$'
mise exec -- go test ./...
```

- RED inicial: antes da implementação, o teste não compilou porque `replicaAdapter` não existia.
- GREEN: os testes com `httptest.Server` passaram para o patch mínimo e para a propagação de erro. A suíte completa `go test ./...` também passou, cobrindo coleta e avaliação anteriores.
- Checagem de regressão: o tipo do patch foi temporariamente trocado de strategic merge para merge patch. `TestReplicaAdapterPatchesOnlyDesiredReplicas` falhou ao receber `application/merge-patch+json` em vez de `application/strategic-merge-patch+json`. O tipo correto foi restaurado e a suíte completa passou novamente.
- Não houve chamadas ao cluster nem uso de kubeconfig ou Vagrant. O teste abre somente um servidor HTTP local; a execução ocorreu com permissão para acessar o cache Go e o loopback, bloqueados pelo sandbox.
- Na revisão final, removi a serialização genérica via `json.Marshal` e seu erro impossível para este corpo fixo com `int32`; `fmt.Sprintf` produz diretamente o patch mínimo. `gofmt -w cmd/csa-go-http/adapt_replicas.go`, `mise exec -- go test ./cmd/csa-go-http -run '^TestReplicaAdapter'` e `mise exec -- go test ./...` passaram. A primeira tentativa de teste no sandbox foi bloqueada pelo cache Go somente leitura; as repetições autorizadas passaram.

## Validação funcional sequencial no cluster

Em 13/10/2026, depois do teste Python, o adaptador Go foi chamado contra o Deployment temporário `csa-replica-smoke-20261013/replica-target` no cluster Vagrant. A fixture e os comandos de criação/remoção estão em [histórico CSA Python HTTP](../csa-python-http/historico-de-desenvolvimento.md#validação-funcional-sequencial-no-cluster). O runner temporário criou um cliente dinâmico com o kubeconfig Vagrant, chamou `replicaAdapter.adapt()` e leu de volta `spec.replicas`. Ele foi removido depois do smoke test para não ampliar a implementação com um executável de teste.

Executados na pasta `autoscalers/csa-go-http`:

```sh
KUBECONFIG=/home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config CSA_NAMESPACE=csa-replica-smoke-20261013 CSA_DEPLOYMENT_NAME=replica-target CSA_TEST_REPLICAS=3 mise exec -- go run ./cmd/csa-go-http/adapt_replicas.go ./cmd/csa-go-http/metrics.go ./cmd/csa-go-http/cluster_smoke.go
KUBECONFIG=/home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config CSA_NAMESPACE=csa-replica-smoke-20261013 CSA_DEPLOYMENT_NAME=replica-target CSA_TEST_REPLICAS=0 mise exec -- go run ./cmd/csa-go-http/adapt_replicas.go ./cmd/csa-go-http/metrics.go ./cmd/csa-go-http/cluster_smoke.go
```

As duas execuções verificaram no cluster os valores `3` e `0`. A adaptação Python havia deixado a fixture em zero antes da chamada Go, e o teste Java começou depois da restauração Go. O cluster não recebeu mudanças em `kube-znn`; nenhum teste de carga foi executado. `go.mod` e `go.sum` não receberam dependências para o teste funcional.

## Atividade 4 — Empacotamento para testes futuros

### Decisões

- O operator existente instala a CRD compartilhada `customselfadapters.custom-self-adapter.net`. Esta implementação adiciona instâncias `CustomSelfAdapter`; não copia nem altera a definição da CRD.
- Cada instância cria um Pod com um único container do serviço Go HTTP, conforme a arquitetura independente registrada acima. Não configura o executor HTTP do runtime genérico nem adiciona um segundo container.
- `Dockerfile` compila o servidor com Go 1.25 para Linux amd64 e o copia para uma imagem final `scratch`. A imagem inclui o bundle de CAs do builder para conexões TLS; executa como UID/GID não-root e inicia `/csa-go-http` na porta 8080. `TAG` escolhe o profile copiado para `/config.yaml`.
- Os profiles `h`, `hq`, `v` e `vq` mantêm os limites e a métrica dos cenários Go existentes, e declaram as estratégias correspondentes. A aplicação ainda não lê `/config.yaml`, não agenda um ciclo e não tem rotas operacionais além de `GET /health`. `adapt_cpu` e `adapt_tag` ainda não têm adaptadores HTTP; os perfis que os habilitam são apenas preparação para atividades futuras.
- Os manifests informam `CSA_DEPLOYMENT_NAME`, `CSA_METRIC_NAME` e `CSA_TARGET_VALUE` em `spec.config`. O operator injeta `CSA_NAMESPACE` com o namespace do recurso. As sondas consultam `/health`, que é a única rota atual.
- `roleRequiresMetricsServer` fica omitido e usa o default `false`. `additionalRoleRules` permite `get` e `list` apenas para `znn_latency_ms_p95` em `external.metrics.k8s.io`. O operator concede acesso padrão mais amplo a Pods e Deployments para cada CSA; isso é comportamento do operator e não pode ser reduzido por estes manifests.
- Os manifests usam o mesmo nome `csa-znn-go-http`, assim cada cenário substitui o anterior ao ser aplicado. Os quatro não devem ser executados simultaneamente contra o mesmo Deployment.

### Comandos de inspeção

Executados na worktree `.worktrees/csa-go-http` e, para o código do operator, no repositório irmão:

```sh
git status --short --branch
rg --files autoscalers/csa-go-http autoscalers/csa-go autoscalers/csa
rg -n "CustomSelfAdapter|customselfadapter|initialData|metricInterval|containerName" . --glob '*.yaml' --glob '*.go'
sed -n '1,220p' autoscalers/csa-go/Dockerfile
sed -n '1,220p' autoscalers/csa-go/custom-selfadapter-template.yaml
sed -n '1,230p' ../../../custom-self-adapter/custom-self-adapter/internal/execute/http/http.go
sed -n '190,335p' ../../../custom-self-adapter/custom-self-adapter-operator/internal/controller/customselfadapter_controller.go
```

A inspeção confirmou que o schema da CRD aceita `spec.config`, `spec.template`, `spec.scaleTargetRef` e `spec.additionalRoleRules`; o controller transforma `spec.config` em variáveis de ambiente. O executor HTTP usa uma requisição com método e URL configuráveis, envia o valor recebido como corpo quando `parameterMode: body`, devolve o corpo da resposta e compara o status com `successCodes`. Esses campos não são necessários para a arquitetura standalone adotada nesta etapa.

### Arquivos e comandos para uso futuro

- `autoscalers/csa-go-http/Dockerfile` — build estático e seleção do profile por argumento.
- `autoscalers/csa-go-http/profiles/{h,hq,v,vq}.yaml` — parâmetros e conjuntos de estratégias dos cenários.
- `autoscalers/csa-go-http/custom-selfadapter-{h,hq,v,vq}.yaml` — recursos CustomSelfAdapter para provisionar o Pod e as permissões usadas pela coleta.

Exemplos de comandos previstos para quando a imagem e os endpoints estiverem prontos. Não foram executados nesta atividade:

```sh
cd autoscalers/csa-go-http
docker build --build-arg TAG=h -t registry.k8s.lab/csa-znn-go-http:h .
kubectl apply -f custom-selfadapter-h.yaml
```

### Validação

- Revisão estática dos perfis e dos campos comparando-os com o schema CRD compartilhado e com o controller instalado no repositório irmão.
- `git diff --check` e inspeção de whitespace executados após as alterações.
- Não foram executados testes, build Go/Docker, push de imagem, `kubectl apply` ou testes no cluster, conforme o escopo desta atividade.
