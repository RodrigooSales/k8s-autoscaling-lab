# CSA Go HTTP

Implementação Go dos callbacks HTTP do Custom Self-Adapter. O ciclo usa dois Pods: o runtime genérico coleta a métrica e chama os callbacks `metric`, `evaluate` e `adapt`; o servidor Go os expõe pelo Service `csa-go-http-api`.

`cmd/csa-go-http/main.go` configura `GET /health`, `POST /metric`, `POST /evaluate` e `POST /adapt/replicas`. O runtime envia os dados de Kubernetes no corpo da chamada; `metrics.go` interpreta esse payload e não consulta o Metrics Server. `evaluate.go` mantém os limiares e limites da implementação Go tradicional. Sem decisão, `/evaluate` responde `{}`. O runtime interpreta isso como ausência de estratégia e não chama `Adapt`.

`adapt_replicas.go` aplica somente `Deployment.spec.replicas`. Por enquanto, todos os profiles habilitam apenas réplicas; adaptadores de CPU e tag ainda não existem nesta implementação HTTP.

## Configuração e execução no cluster

`profiles/{h,hq,v,vq}.yaml` usa o schema de callbacks do runtime genérico, com URLs HTTP e `kubernetesMetricSpecs` para o runtime buscar a métrica. Cada `custom-selfadapter-<perfil>.yaml` contém os recursos `CustomSelfAdapter` do servidor Go e do runtime genérico; o último monta o profile em `/config.yaml`. `service.yaml` seleciona o Pod Go pelas labels aplicadas pelo operador.

Antes de aplicar um perfil, publique a imagem do runtime com suporte a avaliação vazia como `registry.k8s.lab/custom-self-adapter:alpine-empty-evaluation`, crie o ConfigMap e aplique Service e manifest:

```sh
kubectl create configmap csa-go-http-runtime-config-h \
  --from-file=config.yaml=autoscalers/csa-go-http/profiles/h.yaml \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f autoscalers/csa-go-http/service.yaml
kubectl apply -f autoscalers/csa-go-http/custom-selfadapter-h.yaml
```

Use os sufixos `hq`, `v` ou `vq` para os demais perfis. As imagens Go e do runtime também precisam estar publicadas no registry acessível aos nós.

## Desenvolvimento local

```sh
mise exec -- go test ./...
docker build --build-arg TAG=h -t csa-go-http:h autoscalers/csa-go-http
```

O Dockerfile compila um binário estático e inclui o profile selecionado. O histórico de decisões, comandos e validações está em [docs/csa-go-http/historico-de-desenvolvimento.md](../../docs/csa-go-http/historico-de-desenvolvimento.md).
