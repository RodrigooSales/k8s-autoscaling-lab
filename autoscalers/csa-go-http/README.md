# CSA Go HTTP

Implementação HTTP em Go. `cmd/csa-go-http/main.go` inicia o servidor e expõe `GET /health`. A coleta fica em `cmd/csa-go-http/metrics.go`; a avaliação pura fica em `cmd/csa-go-http/evaluate.go`; a adaptação de réplicas fica em `cmd/csa-go-http/adapt_replicas.go`. Essas operações ainda não estão ligadas a rotas nem a um ciclo periódico.

`metricCollector.collect()` lê a métrica externa configurada e depois `spec.replicas` do Deployment. Soma todos os valores da métrica e os converte para milésimos. `CSA_NAMESPACE`, `CSA_DEPLOYMENT_NAME` e `CSA_METRIC_NAME` são obrigatórios. `CSA_METRIC_SELECTOR` é opcional e `CSA_TARGET_VALUE` assume `1000` se não for definido. O cliente Kubernetes é criado na primeira coleta e reutilizado; o health check não carrega configuração do cluster.

`evaluate()` recebe um snapshot da coleta e o estado/configuração de CPU e réplicas. Retorna a decisão no formato `{strategy, parameters}`, ou `nil` quando não há adaptação. A avaliação não consulta Kubernetes.

`replicaAdapter.adapt()` aplica a quantidade decidida com um strategic merge patch mínimo em `Deployment.spec.replicas`. Ele usa o cliente Kubernetes dinâmico e propaga falhas com o namespace e o nome do Deployment. O ciclo futuro fornecerá a decisão tipada pela avaliação.

O projeto fixa Go 1.25 por meio de `mise.toml` e usa `client-go` e `apimachinery` para acessar a API Kubernetes. A partir desta pasta:

```sh
mise exec -- go run ./cmd/csa-go-http
mise exec -- go test ./...
```

O `Dockerfile` gera um binário estático e seleciona `profiles/{h,hq,v,vq}.yaml` com `--build-arg TAG=<perfil>`. Os manifests `custom-selfadapter-{h,hq,v,vq}.yaml` provisionam um Pod independente por meio do CustomSelfAdapter existente. `spec.config` fornece os nomes do Deployment e da métrica ao container; o operator já injeta `CSA_NAMESPACE` a partir do namespace do recurso.

Os quatro profiles descrevem a configuração da aplicação HTTP independente: `interval`, `minReplicas`, `maxReplicas`, `maxCPU`, `metric` (`name`, `selector`, `targetValue`) e `enabled_strategies`. Os valores-base são intervalo de 5000 ms, réplicas de 1 a 5, CPU máxima de 750 millicores e métrica `znn_latency_ms_p95` com alvo 1000 e seletor vazio. Somente `enabled_strategies` varia por cenário: H usa réplicas, HQ usa réplicas e tag, V usa CPU, e VQ usa CPU e tag. Os YAMLs não configuram callbacks HTTP do executor genérico antigo.

O CustomSelfAdapter existente provisiona o Pod, ServiceAccount, Role e RoleBinding. O Role padrão já permite operações amplas sobre Pods e Deployments; estes manifests mantêm `roleRequiresMetricsServer` falso e adicionam somente `get`/`list` para a métrica externa nomeada. Não é necessário um Service Kubernetes: o processo HTTP independente será dono do ciclo periódico dentro do próprio Pod.

A aplicação ainda não carrega `/config.yaml` nem executa um ciclo periódico. Coleta e adaptação leem configuração do ambiente, enquanto `evaluate()` recebe limites e estratégias por argumento. Hoje só `GET /health` está exposto; coleta, avaliação e adaptação não estão ligadas a rotas ou ao ciclo. As estratégias CPU e tag também não têm adaptadores nesta implementação. Portanto, os profiles e manifests preparam o empacotamento para testes futuros, mas ainda não executam o autoscaling completo.

O histórico das decisões, comandos e validações fica em [docs/csa-go-http/historico-de-desenvolvimento.md](../../docs/csa-go-http/historico-de-desenvolvimento.md).
