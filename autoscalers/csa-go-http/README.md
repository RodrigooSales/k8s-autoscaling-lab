# CSA Go HTTP

Implementação Go dos callbacks HTTP do Custom Self-Adapter. O ciclo usa dois Pods: o runtime genérico coleta a métrica e executa `metric`, `evaluate` e `adapt`; o servidor Go atende os callbacks pelo Service `csa-go-http-api`.

`cmd/csa-go-http/main.go` publica `GET /health`, `POST /metric`, `POST /evaluate` e `POST /adapt/replicas`. `metrics.go` interpreta a entrada enviada pelo runtime, `evaluate.go` aplica os limiares existentes e `adapt_replicas.go` altera somente `Deployment.spec.replicas`. Quando não há decisão, `/evaluate` responde `204 No Content` sem corpo. O runtime tenta interpretar essa saída vazia como JSON e falha; o callback shell também produz saída vazia nesse caso e falha no mesmo parser. Essa equivalência foi reproduzida com o evaluator do runtime, que não foi alterado.

## Profiles e cenários

Cada arquivo em `profiles/` preserva os blocos `metric`, `evaluate` e `adapt` usados pelo runtime shell, com os executores HTTP apontando para o Service. As estratégias habilitadas seguem os cenários Go tradicionais:

| Profile | Estratégias habilitadas |
| --- | --- |
| `h` | `adapt_replicas` |
| `hq` | `adapt_replicas`, `adapt_tag` |
| `v` | `adapt_cpu` |
| `vq` | `adapt_cpu`, `adapt_tag` |

Só o callback de réplicas está implementado nesta versão. Portanto `h` é o único profile cujas estratégias selecionadas têm uma rota correspondente; `hq`, `v` e `vq` documentam a configuração de cenário e dependem dos adaptadores de tag/CPU futuros.

## Imagens e cluster

Cada manifest `custom-selfadapter-<profile>.yaml` contém dois recursos `CustomSelfAdapter`: a API e o runtime. `runtime.Dockerfile` deriva da imagem genérica existente e embute o profile selecionado em `/config.yaml`. Assim, não há ConfigMap. `service.yaml` é o Service explícito que aponta para os Pods da API; o operador continua responsável pelos Pods e pelo RBAC de cada recurso.

Exemplo de build e publicação do profile `h`, executado na pasta `autoscalers/csa-go-http`:

```sh
docker build --build-arg TAG=h -t registry.k8s.lab/csa-znn-go-http:h .
docker build -f runtime.Dockerfile --build-arg PROFILE=h -t registry.k8s.lab/csa-znn-go-http-runtime:h .
docker push registry.k8s.lab/csa-znn-go-http:h
docker push registry.k8s.lab/csa-znn-go-http-runtime:h
kubectl apply -f service.yaml
kubectl apply -f custom-selfadapter-h.yaml
```

Para outro cenário, use o mesmo sufixo nos dois builds e no manifest. O runtime base `registry.k8s.lab/custom-self-adapter:alpine-latest` está fixado pelo digest em `runtime.Dockerfile` e precisa estar disponível no registry. O histórico de decisões, comandos e validações está em [docs/csa-go-http/historico-de-desenvolvimento.md](../../docs/csa-go-http/historico-de-desenvolvimento.md).
