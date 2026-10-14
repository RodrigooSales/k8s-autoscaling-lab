# CSA Python HTTP

Implementação Python dos callbacks HTTP do Custom Self-Adapter. O ciclo usa dois Pods: o runtime genérico coleta a métrica e executa `metric`, `evaluate` e `adapt`; a aplicação Python atende os callbacks pelo Service `csa-python-http-api`.

`main.py` expõe `GET /health`, `POST /metric`, `POST /evaluate` e `POST /adapt/replicas`. `metrics.py` interpreta a entrada enviada pelo runtime, `evaluate.py` aplica os limiares existentes e `adapt_replicas.py` altera somente `Deployment.spec.replicas`. Quando não há decisão, `/evaluate` responde `{}`; o runtime genérico atual pode registrar erro ao tentar adaptar uma avaliação sem estratégia. Essa resposta mantém o contrato da avaliação shell. O runtime externo não foi alterado.

## Profiles e cenários

Cada arquivo em `profiles/` preserva os blocos `metric`, `evaluate` e `adapt` usados pelo runtime shell, com os executores HTTP apontando para o Service. As estratégias habilitadas seguem os cenários Python tradicionais:

| Profile | Estratégias habilitadas |
| --- | --- |
| `h` | `adapt_replicas` |
| `hq` | `adapt_replicas`, `adapt_tag` |
| `v` | `adapt_cpu` |
| `vq` | `adapt_cpu`, `adapt_tag` |

Só o callback de réplicas está implementado nesta versão. Portanto `h` é o único profile cujas estratégias selecionadas têm uma rota correspondente; `hq`, `v` e `vq` documentam a configuração de cenário e dependem dos adaptadores de tag/CPU futuros.

## Imagens e cluster

Cada manifest `custom-selfadapter-<profile>.yaml` contém dois recursos `CustomSelfAdapter`: a API e o runtime. `runtime.Dockerfile` deriva da imagem genérica existente e embute o profile selecionado em `/config.yaml`. Assim, não há ConfigMap. `service.yaml` é o Service explícito que aponta para os Pods da API; o operador continua responsável pelos Pods e pelo RBAC de cada recurso.

Exemplo de build e publicação do profile `h`, executado na raiz do repositório:

```sh
docker build --build-arg PROFILE=h -t registry.k8s.lab/csa-python-http:h autoscalers/csa-python-http
docker build -f autoscalers/csa-python-http/runtime.Dockerfile --build-arg PROFILE=h -t registry.k8s.lab/csa-python-http-runtime:h autoscalers/csa-python-http
docker push registry.k8s.lab/csa-python-http:h
docker push registry.k8s.lab/csa-python-http-runtime:h
kubectl apply -f autoscalers/csa-python-http/service.yaml
kubectl apply -f autoscalers/csa-python-http/custom-selfadapter-h.yaml
```

Para outro cenário, use o mesmo sufixo no argumento `PROFILE`, nas duas imagens e no manifest. O runtime base `registry.k8s.lab/custom-self-adapter:alpine-latest` está fixado pelo digest em `runtime.Dockerfile` e precisa estar disponível no registry. O histórico de decisões, comandos e validações está em [docs/csa-python-http/historico-de-desenvolvimento.md](../../docs/csa-python-http/historico-de-desenvolvimento.md).
