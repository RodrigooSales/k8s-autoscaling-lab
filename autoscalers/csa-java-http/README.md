# CSA Java HTTP

Implementação Java dos callbacks HTTP do Custom Self-Adapter. O ciclo usa dois Pods: o runtime genérico coleta a métrica e chama `metric`, `evaluate` e `adapt`; a aplicação Spring Boot os expõe pelo Service `csa-java-http-api`.

`CallbackController` publica health, coleta, avaliação e adaptação de réplicas. `MetricCollectionService` interpreta os valores enviados pelo runtime, sem consultar o Metrics Server. `EvaluationService` preserva os limiares e limites da implementação Java tradicional. Quando nenhuma adaptação é selecionada, `/evaluate` responde `{}`; o runtime reconhece a decisão vazia e não executa `Adapt`.

`ReplicaAdaptationService` implementa somente a mudança de réplicas. Todos os profiles habilitam apenas `adapt_replicas` nesta etapa; as adaptações de CPU e tag ainda não estão disponíveis nesta versão HTTP.

## Configuração e execução no cluster

`profiles/{h,hq,v,vq}.yaml` mantém o schema de callbacks do runtime genérico, com URLs HTTP para o Service e `kubernetesMetricSpecs` para a coleta no runtime. Cada manifest `custom-selfadapter-<perfil>.yaml` contém os recursos `CustomSelfAdapter` da aplicação Java e do runtime; o runtime monta o profile em `/config.yaml`. `service.yaml` seleciona o Pod Java pelas labels aplicadas pelo operador.

Antes de aplicar um perfil, publique a imagem do runtime com suporte a avaliação vazia como `registry.k8s.lab/custom-self-adapter:alpine-empty-evaluation`, crie o ConfigMap e aplique Service e manifest:

```sh
kubectl create configmap csa-java-http-runtime-config-h \
  --from-file=config.yaml=autoscalers/csa-java-http/profiles/h.yaml \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f autoscalers/csa-java-http/service.yaml
kubectl apply -f autoscalers/csa-java-http/custom-selfadapter-h.yaml
```

Use os sufixos `hq`, `v` ou `vq` nos demais perfis. As imagens Java e do runtime também precisam estar no registry acessível ao cluster.

## Desenvolvimento local

```sh
./gradlew test
docker build --build-arg PROFILE=h -t csa-java-http:h autoscalers/csa-java-http
```

O Dockerfile cria o `bootJar`, usa Java 21 e inclui o profile escolhido. O histórico de decisões, comandos e validações está em [docs/csa-java-http/historico-de-desenvolvimento.md](../../docs/csa-java-http/historico-de-desenvolvimento.md).
