# Execução de carga CSA Go

Data: 2026-09-24. Cluster: `kubernetes-admin@kubernetes`, Kubernetes v1.35.3.
Locust 2.41.6, Python 3.13.11, quatro workers. Foi executada uma iteração do seletor Go,
com uma rodada Locust de aproximadamente cinco minutos por perfil. Nenhuma implementação
Python ou Java foi submetida a carga nesta execução.

## Interpretação de performance e timeouts

Há evidência de que o CSA Go executa as adaptações em carga real: `h` escalou réplicas,
`hq` combinou escala e alteração de tag, e `v`/`vq` redimensionaram CPU. Isso mostra potencial
funcional para continuar a avaliação. Esta rodada não demonstra boa performance comparativa:
houve de 2,32% a 16,69% de falhas HTTP, p95 de 1,3 a 1,8 s e máximos de até 61,1 s, e não
houve rodada Python ou Java equivalente. As taxas especialmente altas em `hq` 50% e `vq`
precisam ser entendidas antes de atribuir o resultado ao adaptador Go.

Nos logs disponíveis não foi identificado timeout das etapas `metric`, `evaluate` ou
`adapt_*` do executor CSA Go. Os HTTP 504 listados abaixo são respostas da aplicação durante
a carga; também foram observados erros de métrica indisponível durante rollouts e stdout vazio
em ciclos sem adaptação. Esses eventos não são evidência de timeout do processo Go. Como a
execução não registrou duração individual das etapas, ela também não calibra os timeouts.

## Comando e cenário

Antes da execução, o contexto era `kubernetes-admin@kubernetes`, os quatro nós estavam
Ready, não havia CR CSA ativo, e `default/kube-znn` tinha uma réplica Ready com imagem
`znn:400k` e limites `750m`/`64Mi`. Não havia arquivo anterior com prefixo `csa_go` em
`tests/results/`. A conferência inicial de processos foi repetida com `pgrep -x locust` após
um filtro amplo ter correspondido ao próprio shell de preflight; não havia Locust ativo.

```sh
kubectl config current-context
kubectl get nodes
kubectl get customselfadapters.custom-self-adapter.net -A -o json | jq '[.items[]|{namespace:.metadata.namespace,name:.metadata.name,target:.spec.scaleTargetRef}]'
kubectl -n default get deployment kube-znn -o json | jq '{generation:.metadata.generation,replicas:.spec.replicas,ready:.status.readyReplicas,containers:[.spec.template.spec.containers[]|{name,image,requests:.resources.requests,limits:.resources.limits}]}'
kubectl -n default get pods -l app=kube-znn -o wide
find tests/results -maxdepth 1 -type f -name '*csa_go*' -print | sort
if pgrep -x locust >/dev/null; then pgrep -ax locust; else echo 'No Locust executable process found'; fi
.venv/bin/locust --version
```

Executado a partir da raiz do laboratório, com a virtualenv ativa:

```sh
source .venv/bin/activate && ./run_tests_by_cenary.sh csa-go 1
```

O seletor `csa-go` percorreu `h`, `hq` com rollout 25%, `hq` com rollout 50%, `v` e `vq`.
Cada cenário usou `tests/scenarios/locustfile.py`, quatro processos, URL `https://znn.k8s.lab`
e a forma de onda `DoubleWave`: 20, 100, 60, 120 e 20 usuários em estágios de 30, 90, 80,
50 e 50 segundos. O alvo foi recriado pelo overlay `800k` antes de cada perfil. Os CSVs foram
salvos com nomes exclusivos em `tests/results/`:

| Perfil | CSV |
|---|---|
| h | [01_3_csa_go_h.csv](../../../tests/results/01_3_csa_go_h.csv) |
| hq, rollout 25% | [01_3_csa_go_hq_25.csv](../../../tests/results/01_3_csa_go_hq_25.csv) |
| hq, rollout 50% | [01_3_csa_go_hq_50.csv](../../../tests/results/01_3_csa_go_hq_50.csv) |
| v | [01_6_csa_go_v.csv](../../../tests/results/01_6_csa_go_v.csv) |
| vq | [01_6_csa_go_vq.csv](../../../tests/results/01_6_csa_go_vq.csv) |

Os scripts `run_tests*` agora formatam o número da iteração com largura mínima de dois
dígitos. Assim, uma execução única recebe prefixo `01`, enquanto as iterações seguintes
mantêm `02` a `50`. Os cinco CSVs desta rodada foram renomeados de `1_...` para `01_...`;
seu conteúdo não foi alterado.

Depois da correção, a sintaxe dos scripts foi verificada sem executá-los. A formatação foi
conferida para os valores 1, 9, 10, 50 e 100; confirmou `01`, `09`, `10`, `50` e `100`.
Os cinco arquivos renomeados existem, não estão vazios e não restaram CSVs Go com prefixo
`1_`.

```sh
for script in ../run_tests.sh ../run_tests_csa.sh ../run_tests_by_cenary.sh; do
    bash -n "$script" || exit
done
for number in 1 9 10 50 100; do
    printf -v iteration '%02d' "$number"
    printf '%s -> %s\n' "$number" "$iteration"
done
python3 - <<'PY'
from pathlib import Path

scripts = [Path('../run_tests.sh'), Path('../run_tests_csa.sh'), Path('../run_tests_by_cenary.sh')]
for script in scripts:
    source = script.read_text()
    assert 'for iteration_number in $(seq 1 ' in source, script
    assert "printf -v iteration '%02d' \"$iteration_number\"" in source, script
    assert 'run_test_suite ${iteration}' in source or 'run_test_suite "$iteration"' in source, script
files = sorted(Path('../tests/results').glob('01_*_csa_go_*.csv'))
assert len(files) == 5, files
assert not list(Path('../tests/results').glob('1_*_csa_go_*.csv'))
for csv in files:
    assert csv.stat().st_size > 0, csv
    print(f'{csv.name}: {csv.stat().st_size} bytes')
PY
git diff --check
git diff --quiet -- csa csa-java
```

Resultado: os três `bash -n` passaram; o teste estático encontrou os cinco CSVs e os
prefixos esperados; `git diff --check` passou e as árvores Python/Java não têm diff.

## Resultados Locust

| Perfil | Requisições | Falhas | % falhas | Média | p95 Locust | Máximo | req/s | p95 Prometheus máx. |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| h | 25.994 | 671 | 2,58% | 788 ms | 1.800 ms | 61.147 ms | 86,37 | 2.706 ms |
| hq 25% | 42.614 | 1.496 | 3,51% | 476 ms | 1.300 ms | 15.086 ms | 141,96 | 1.619 ms |
| hq 50% | 38.536 | 6.143 | 15,94% | 531 ms | 1.600 ms | 15.050 ms | 128,02 | 2.942 ms |
| v | 27.258 | 633 | 2,32% | 752 ms | 1.400 ms | 3.802 ms | 90,57 | 1.737 ms |
| vq | 34.920 | 5.828 | 16,69% | 585 ms | 1.300 ms | 15.269 ms | 116,09 | 1.957 ms |

Os percentis Locust são tempos de resposta de `GET /news.php`; a última coluna é o maior
valor da série Prometheus `znn_latency_ms_p95` no CSV correspondente. O total de linhas da
série Locust nos CSVs não é uma contagem de requisições: o coletor local deduplica amostras
com a mesma combinação de timestamp, duração, tamanho e status.

Todas as rodadas Locust reportaram `exit code 1` e falhas, mas ainda salvaram o CSV. O wrapper
continuou para os cenários seguintes e imprimiu o encerramento da iteração sem propagar a
falha de Locust; essa mensagem de encerramento não equivale a uma execução de carga sem erros.
Os status reportados foram:

| Perfil | HTTP 500 | HTTP 502 | HTTP 504 | resposta encerrada |
|---|---:|---:|---:|---:|
| h | 628 | 38 | 2 | 3 |
| hq 25% | 696 | 730 | 67 | 3 |
| hq 50% | 566 | 5.283 | 283 | 11 |
| v | 633 | 0 | 0 | 0 |
| vq | 615 | 4.972 | 236 | 5 |

## Comportamento observado

- **h:** com `znn:800k`, `150m` por container e uma réplica inicial, o Deployment escalou até
  cinco réplicas durante o pico; a métrica externa chegou a aproximadamente 2.382 ms para alvo
  de 1.000 ms. Em ciclos abaixo de 0,90 do alvo, com réplicas já no mínimo e sem outra
  estratégia habilitada, evaluate não escreveu resultado JSON. O runtime registrou erro ao
  tentar interpretar stdout vazio. A referência Python também termina sem JSON nesse ramo
  (`No adaptation selected` em [evaluate.py](../../csa/scripts/evaluate.py)); não é uma
  divergência exclusiva do Go.
- **hq 25% e 50%:** ambas atingiram cinco réplicas durante o pico e reduziram a imagem de
  `800k` até `100k`; os limites permaneceram em `150m`. O estado inicial foi
  `{"cpu_limit":150,"tag":"800k"}`. As falhas foram majoritariamente 500/502/504, com a
  maior taxa no rollout 50% nesta única iteração.
- **v:** sem escalonamento de réplicas, os limites efetivos de CPU dos Pods subiram de `150m`
  para `346m` e depois `750m` em `znn` e nginx. O Deployment template continuou em `150m`, pois
  este perfil usa o subresource `pods/resize`; o estado inicial salvo foi `150m`.
- **vq:** a CPU chegou a `750m` e a imagem desceu de `800k`, passando por `600k` e `200k` até
  `100k` durante o pico. Rollouts concluíram com uma réplica Ready.

Houve ciclos em que a API de métricas externas ficou temporariamente sem amostras durante as
recriações/rollouts; o runtime registrou falha ao buscar a métrica e retomou as decisões quando
ela voltou. No início da matriz, mensagens NotFound ao remover HPA, CSA, VPA e recursos antigos
eram esperadas: o namespace não tinha esses adaptadores e o script recriou a aplicação em
seguida.

## Estado depois da execução e limites da conclusão

Ao final, não havia CR CSA Go nem Pod do adaptador. `default/kube-znn` estava com uma réplica
Ready, `znn:400k`, nginx `openresty/openresty:alpine-fat`, requests `50m`/`16Mi` e limites
`750m`/`64Mi`, igual ao estado observado antes da execução. Os CSVs continuam em
`tests/results/`.

Verificações finais:

```sh
kubectl get customselfadapters.custom-self-adapter.net -A -o name
kubectl -n default get pods -o wide | rg 'csa-znn-go|kube-znn'
kubectl -n default get deployment kube-znn -o json | jq '{generation:.metadata.generation,replicas:.spec.replicas,ready:.status.readyReplicas,containers:[.spec.template.spec.containers[]|{name,image,requests:.resources.requests,limits:.resources.limits}]}'
kubectl get --raw '/apis/external.metrics.k8s.io/v1beta1/namespaces/default/znn_latency_ms_p95' | jq '.items | length'
find tests/results -maxdepth 1 -type f -name '01_*_csa_go_*.csv' -printf '%f %s bytes\n' | sort
```

Esta foi uma única iteração, sem repetição e sem execução simultânea das versões Python/Java.
As taxas de falha, em especial nos dois cenários de maior rollout combinado, tornam este
resultado uma observação de comportamento e confiabilidade sob a carga, não uma conclusão
comparativa de performance. Investigações de causa para HTTP 500/502/504 e comparação com as
outras implementações exigem execuções controladas adicionais.
