# CSA Go HTTP

Implementação Go dos callbacks HTTP do Custom Self-Adapter. O ciclo usa dois Pods: o runtime genérico coleta a métrica e executa `metric`, `evaluate` e `adapt`; o servidor Go atende os callbacks pelo Service `csa-go-http-api`.

`cmd/csa-go-http/main.go` publica `GET /health`, `POST /metric`, `POST /evaluate` e os callbacks de réplica, CPU e tag. `metrics.go` interpreta a entrada do runtime; `evaluate.go` aplica os limiares; `adapt_replicas.go`, `adapt_cpu.go` e `adapt_tag.go` implementam cada adaptação em seu próprio arquivo. `adapt_state.go` centraliza acesso ao Deployment, Pods e `initialData` do CSA.

CPU usa o mesmo multiplicador, limites inicial/máximo e subrecurso `pods/resize` da versão shell. Tag percorre `100k`, `200k`, `400k`, `600k` e `800k`, respeita o tag inicial e sincroniza limites de CPU quando `update_cpu` é solicitado. Ambas ignoram alterações durante rollout. Quando não há decisão, `/evaluate` responde `204 No Content` sem corpo; o problema conhecido do runtime ao interpretar corpo vazio continua registrado abaixo.

## Profiles e cenários

Cada arquivo em `profiles/` preserva os blocos `metric`, `evaluate` e `adapt` usados pelo runtime shell, com os executores HTTP apontando para o Service. As estratégias habilitadas seguem os cenários Go tradicionais:

| Profile | Estratégias habilitadas |
| --- | --- |
| `h` | `adapt_replicas` |
| `hq` | `adapt_replicas`, `adapt_tag` |
| `v` | `adapt_cpu` |
| `vq` | `adapt_cpu`, `adapt_tag` |

Os quatro profiles têm rotas correspondentes às estratégias habilitadas. A API recebe a regra adicional `pods/resize` para executar CPU; o runtime genérico mantém sua própria identidade e permissões.

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
