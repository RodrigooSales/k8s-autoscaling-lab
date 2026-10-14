# CSA Python HTTP

Implementação Python dos callbacks HTTP do Custom Self-Adapter. O ciclo tem dois Pods: o runtime genérico coleta a métrica e executa `metric`, `evaluate` e `adapt`; o Pod desta aplicação expõe os callbacks pelo Service `csa-python-http-api`.

`main.py` expõe `GET /health`, `POST /metric`, `POST /evaluate` e `POST /adapt/replicas`. A métrica já vem no payload enviado pelo runtime; `metrics.py` normaliza esse payload sem consultar o Metrics Server. `evaluate.py` mantém os limiares e limites da implementação Python tradicional. Quando não há decisão, `/evaluate` responde `{}`. O runtime ignora uma avaliação sem estratégia e encerra o ciclo sem executar `Adapt`.

`adapt_replicas.py` aplica somente `Deployment.spec.replicas`. Neste estágio, os quatro profiles habilitam somente `adapt_replicas`; CPU e tag não estão implementadas nesta versão HTTP.

## Configuração e execução no cluster

`profiles/{h,hq,v,vq}.yaml` preserva o schema de callbacks do runtime genérico, com URLs HTTP apontando para o Service e `kubernetesMetricSpecs` para a coleta feita pelo runtime. Cada manifest `custom-selfadapter-<perfil>.yaml` contém dois recursos `CustomSelfAdapter`: o servidor Python e o runtime genérico com o profile montado em `/config.yaml`. `service.yaml` seleciona o Pod Python pelas labels que o operador aplica ao recurso.

Antes de aplicar um perfil, publique a imagem do runtime com suporte a avaliação vazia como `registry.k8s.lab/custom-self-adapter:alpine-empty-evaluation`, crie o ConfigMap e aplique o Service e o manifest:

```sh
kubectl create configmap csa-python-http-runtime-config-h \
  --from-file=config.yaml=autoscalers/csa-python-http/profiles/h.yaml \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f autoscalers/csa-python-http/service.yaml
kubectl apply -f autoscalers/csa-python-http/custom-selfadapter-h.yaml
```

Para `hq`, `v` ou `vq`, use o sufixo correspondente no ConfigMap e no manifest. Os recursos ainda precisam das imagens de aplicação publicadas no registry usado pelo cluster.

## Desenvolvimento local

```sh
../../.venv/bin/pytest autoscalers/csa-python-http/tests -q
docker build --build-arg PROFILE=h -t csa-python-http:h autoscalers/csa-python-http
```

O Dockerfile inclui o profile selecionado na imagem Python. O histórico de decisões, comandos e validações está em [docs/csa-python-http/historico-de-desenvolvimento.md](../../docs/csa-python-http/historico-de-desenvolvimento.md).
