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

Os profiles registram os limites, a métrica, o intervalo e as estratégias dos cenários, mas a aplicação ainda não os carrega. Hoje só `GET /health` está exposto; coleta, avaliação e adaptação não estão conectadas ao servidor nem a um ciclo periódico. As estratégias CPU e tag também não têm adaptadores nesta implementação. Portanto, os manifests servem para preparar o empacotamento e os testes futuros, não para executar o autoscaling completo.

O histórico das decisões, comandos e validações fica em [docs/csa-go-http/historico-de-desenvolvimento.md](../../docs/csa-go-http/historico-de-desenvolvimento.md).
