# Integração e timeouts — observações das entregas 1, 3, 6 e 7

Data/revisões: [levantamento](levantamento.md). A investigação das referências foi local;
as validações de cluster e prontidão estão registradas abaixo. A validação inicial de
prontidão não executou carga; uma iteração Go autorizada depois está documentada em
[execucao-carga.md](execucao-carga.md). Comandos de investigação anteriores em
[registro](comandos-investigacao.md).

## Runtime e operador

O [executor shell](../../../../custom-self-adapter/internal/execute/shell/shell.go#L55)
inicia um processo por etapa e captura stdout/stderr. O timer usa timeout em milissegundos
e é armado após cmd.Start(). No timeout, o processo é morto e a execução é reportada como erro.
O parâmetro é um limite de espera, não uma duração desejada nem uma aceleração do código.

Quando evaluate retorna vazio, o
[consumidor](../../../../custom-self-adapter/internal/evaluatecalc/evaluatecalc.go#L69)
tenta json.Unmarshal e produz erro. Portanto, a ausência de adaptação do script não equivale
automaticamente a um ciclo bem-sucedido do runtime. Não mudar esse contrato só no Go.

O [operador](../../../../custom-self-adapter-operator/internal/reconcile/reconcile.go#L328)
transfere a anotação initialData para o status e sinaliza sua limpeza.
A anotação não é um cache imediatamente consistente do status.

## Implementação Go — entregas 3 a 6

O processo Go reconhece os cinco modos previstos. A etapa `metric` lê JSON pelo stdin,
escreve o resultado no stdout e não carrega `/config.yaml` nem cria clientes Kubernetes.
Os logs são escritos sincronamente em stderr e `/tmp/adapter.log`. O carregador YAML já
reproduz os diagnósticos da referência para arquivo vazio ou documento nulo; erros de
abertura são retornados ao chamador.

Na entrega 4, `evaluate` já cria configuração in-cluster, lê o Deployment antes de abrir
`/config.yaml`, lista Pods e consulta o status `initialData`; quando o CPU inicial falta,
faz o patch estratégico do Pod do CSA. A operação usa clientes injetáveis nos testes. Na
entrega 6, a integração foi verificada primeiro em namespace descartável e depois, a pedido do
usuário, com os manifests reais em `default` apontando para `kube-znn`. As subentregas 5A–5C
implementam réplicas, CPU e tag com testes unitários isolados. Comandos, imagens e resultados
das duas validações estão em [validacao-funcional.md](validacao-funcional.md).

### SDK Kubernetes Go — comportamento da versão fixada

O módulo usa `client-go` 0.35.3. O cliente CoreV1 e o dinâmico reutilizam o mesmo
`http.Client`, com autenticação e certificados da configuração in-cluster. A lista de Pods,
Deployment e status da CRD usa o cliente dinâmico para manter a presença de campos JSON que
os structs tipados Go não distinguem; a leitura do Pod CSA e o patch usam CoreV1.

No client-go 0.35.3, um REST client sem limiter explícito usa QPS 5 e burst 10 quando a
configuração deixa esses campos em zero. O clientset compartilha um limiter se `QPS > 0`
na configuração; com os valores in-cluster padrão em zero, os REST clients usam seus
limiters locais. O cliente dinâmico cria outro REST client e não compartilha limiter com o
clientset. Cada chamada também pode aguardar token localmente.

O request client define até 10 retries por padrão para respostas com `Retry-After` (inclui
429). Operações GET também podem repetir falhas transitórias de conexão; operações de
escrita não são repetidas automaticamente por essas falhas. O patch de estado nesta entrega
usa PATCH, portanto sua latência pode variar com o API server e não recebe o mesmo retry de
GET. A implementação não define timeout HTTP separado; o limite externo segue o timeout da
fase evaluate no runtime.

Python declara `kubernetes` sem versão fixada em `csa/requirements.txt` e não configura
limite de requisições, retry ou timeout nos scripts inspecionados. Java declara
`client-java` 26.0.0 e chama `Config.fromCluster()` sem configurar esses parâmetros no
código CSA. Como as versões/runtime Python e os defaults exatos dos SDKs das imagens não
estão fixados neste laboratório, não afirmar igualdade de limiter/retry/transport entre as
três linguagens. Antes da comparação sob carga, registrar os pacotes efetivamente instalados
e os digests das imagens. Fontes locais: `client-go/rest/config.go`, `client-go/rest/request.go`,
`csa/requirements.txt`, `csa-java/build.gradle` e `KubernetesClient.java`.

## Parâmetros observados

Fontes: [config Python](../../csa/config.yaml), [config Java](../../csa-java/config.yaml),
[perfis Java](../../csa-java/profiles).

| Parâmetro | Python | Java | Go |
|---|---:|---:|---:|
| interval | 5000 | 5000 | 5000 |
| minReplicas / maxReplicas | 1 / 5 | 1 / 5 | 1 / 5 |
| maxCPU | 750 | 750 | 750 |
| métrica / alvo | znn_latency_ms_p95 / 1000 | znn_latency_ms_p95 / 1000 | znn_latency_ms_p95 / 1000 |
| timeout metric | 1000 ms | 2000 ms | 1000 ms |
| timeout evaluate | 2000 ms | 8000 ms | 2000 ms |
| timeout adapt_replicas | 2000 ms | 8000 ms | 2000 ms |
| timeout adapt_cpu / adapt_tag | 2000 ms | 10000 ms | 2000 ms |
| enabled_strategies no config.yaml padrão | CPU e tag | CPU e tag | CPU e tag |
| requireKubernetesMetrics / logVerbosity | true / 4 | true / 4 | true / 4 |

Os quatro perfis Java e Go variam estratégias: h=réplicas, hq=réplicas+tag,
v=CPU, vq=CPU+tag. O Dockerfile de cada imagem copia o perfil correspondente.
O Dockerfile Python copia seu config.yaml, e seu script fixa TAG=vq.
O README Java relata perfis recuperados de imagens Python publicadas; as imagens existentes
foram inspecionadas na entrega 6 e seus digests estão em
[validacao-funcional.md](validacao-funcional.md). Manifests com tags h/hq/v/vq não provam
sozinhos qual configuração está efetivamente dentro de uma imagem.

Os [manifests](../../csa/custom-selfadapter-template.yaml) usam csa-znn no namespace
default e alvo kube-znn, com requests 128m/128Mi e limits 1024m/1024Mi.
Esses recursos do adaptador são diferentes do maxCPU=750 aplicado aos containers do alvo.
A regra adicional dá patch em pods/resize.

## Política já aprovada, ainda não executada

Go começará com os timeouts Python, explicitamente provisórios.
A calibração futura será feita sobre durações válidas das etapas sob carga executada
pelo usuário: maior duração observada +20%, arredondada para cima em múltiplos de 10 ms,
e confirmação em execuções independentes.

Interrupções não são amostras completas de duração. Não declarar o timeout como mínimo
universal: ele depende do ambiente, do caminho percorrido e do número de Pods. Os timeouts
Python/Java não foram alterados. Go usa os valores provisórios descritos acima; não houve
medição de duração sob carga. A imagem Go e os manifests foram publicados e verificados,
e a confirmação desses valores sob carga segue reservada às execuções do usuário.

## Scripts de carga encontrados

| Arquivo | Observação estática |
|---|---|
| [run_tests.sh](../../../run_tests.sh) | Matriz completa de cenários Python, Java e Go. |
| [run_tests_csa.sh](../../../run_tests_csa.sh) | Cenários CSA Java preservados e cenários CSA Go acrescentados. |
| [run_tests_by_cenary.sh](../../../run_tests_by_cenary.sh) | Seletores base,hpa,csa,csa-java,csa-go,vpa,all. |

Na matriz geral, o bloco Python HQ 25% remove o CSA antes do cenário seguinte.
O bloco Java HQ 25% não o remove antes do HQ 50% (linhas 159–182).
Isso pode preservar estado do CSA entre cenários; o efeito não foi medido.
Nos outros dois scripts, a remoção aparece entre os dois cenários Java HQ. Os cenários novos
Go removem seu CR antes da pausa entre cenários. O estado histórico dos blocos Python/Java
foi preservado.

## Entrega 7 — scripts prontos para a carga

CSA Go foi incluído nos três scripts com os manifests versionados em `autoscalers/csa-go/` e
com identificadores próprios para os CSVs:

| Perfil | `PROM_EXTRACT_NAME` |
|---|---|
| h | `${ITERATION}_3_csa_go_h` |
| hq, rollout 25% | `${ITERATION}_3_csa_go_hq_25` |
| hq, rollout 50% | `${ITERATION}_3_csa_go_hq_50` |
| v | `${ITERATION}_6_csa_go_v` |
| vq | `${ITERATION}_6_csa_go_vq` |

Cada bloco reconfigura o alvo a partir do overlay `800k`, aplica o manifesto CSA Go, mantém
os parâmetros Locust existentes e remove o CR Go após o cenário. Os HQ aplicam os mesmos
valores de `maxSurge`/`maxUnavailable` dos demais cenários. Os três scripts também removem um
CR Go residual no início da matriz. `run_tests_by_cenary.sh` aceita `csa-go` como seletor
isolado e o inclui em `all`; os seletores e a ordem dos cenários existentes foram mantidos.

Verificações executadas, sem invocar os scripts de carga:

```sh
for script in run_tests.sh run_tests_csa.sh run_tests_by_cenary.sh; do bash -n "$script" || exit; echo "$script: syntax OK"; done
kubectl kustomize kube-znn/manifests/overlay/800k | rg -n 'image:|replicas:|maxUnavailable|maxSurge'
for manifest in autoscalers/csa-go/custom-selfadapter-{h,hq,v,vq}.yaml; do kubectl apply --dry-run=server -f "$manifest" -o name; done
python3 - <<'PY'
from pathlib import Path

expected = {
    "h": "${ITERATION}_3_csa_go_h",
    "hq_25": "${ITERATION}_3_csa_go_hq_25",
    "hq_50": "${ITERATION}_3_csa_go_hq_50",
    "v": "${ITERATION}_6_csa_go_v",
    "vq": "${ITERATION}_6_csa_go_vq",
}
for script in map(Path, ("run_tests.sh", "run_tests_csa.sh", "run_tests_by_cenary.sh")):
    text = script.read_text()
    for name in expected.values():
        assert f"PROM_EXTRACT_NAME={name} " in text, (script, name)
    for profile in ("h", "hq", "v", "vq"):
        manifest = f"autoscalers/csa-go/custom-selfadapter-{profile}.yaml"
        assert f"kubectl apply -f {manifest}" in text, (script, manifest, "apply")
        assert f"kubectl delete -f {manifest} --ignore-not-found=true" in text, (script, manifest, "delete")
text = Path("run_tests_by_cenary.sh").read_text()
for token in ('scenario_selected csa-go "$scenario_list"', "csa-java,csa-go,vpa", "csa-go|vpa|all"):
    assert token in text, token
PY
```

Um validador estático em Python conferiu os cinco nomes de saída e referências a apply/delete
em cada script, além de selector, help e validação de argumentos em
`run_tests_by_cenary.sh`. O overlay imprime uma réplica inicial e a imagem
`registry.k8s.lab/znn:800k`; os cenários HQ substituem a estratégia conforme suas porcentagens.
`bash -n` passou nos três arquivos. Os quatro manifests foram aceitos pelo dry-run server da
CRD instalada. Nenhum `run_tests*`, Locust ou teste de carga foi iniciado.

### Prontidão do cluster e registry observada

Comando e estado consultado antes de entregar os scripts:

```sh
kubectl config current-context
kubectl get nodes -o wide
kubectl get customselfadapters.custom-self-adapter.net -A -o json | jq '[.items[]|{namespace:.metadata.namespace,name:.metadata.name,target:.spec.scaleTargetRef}]'
kubectl -n default get deployment kube-znn -o json | jq '{generation:.metadata.generation,replicas:.spec.replicas,ready:.status.readyReplicas,containers:[.spec.template.spec.containers[]|{name,image,requests:.resources.requests,limits:.resources.limits}]}'
kubectl -n default get pods -l app=kube-znn -o wide
curl -fsS http://registry.k8s.lab/v2/csa-znn-go/tags/list | jq -c '{name,tags}'
curl -fsS http://registry.k8s.lab/v2/znn/tags/list | jq -c '{name,tags}'
kubectl get --raw '/apis/external.metrics.k8s.io/v1beta1/namespaces/default/znn_latency_ms_p95' | jq '.items | length'
```

Contexto observado: `kubernetes-admin@kubernetes`; control plane e três workers Kubernetes
v1.35.3 estavam Ready. Não havia CR CSA ativo. `default/kube-znn` estava Ready com uma réplica,
tag `400k`, nginx, requests `50m`/`16Mi` e limites `750m`/`64Mi`. O registry anunciou as tags
Go h/hq/v/vq e tags Znn até `800k`. A métrica externa tinha zero itens porque não havia carga.
Os scripts recriam o alvo com o overlay `800k` para cada cenário, portanto esse estado não foi
alterado durante a preparação. A validação funcional direta dos perfis h, hq e vq segue em
[validacao-funcional.md](validacao-funcional.md).

### Cliente Locust e rota testada

O executável `locust` não está no `PATH` global. O ambiente virtual existente contém a versão
`2.41.6` fixada em `requirements.txt`; os scripts devem ser chamados a partir da raiz do
repositório com `.venv` ativado para resolver `locust`, manifests e o certificado relativo.
`vagrant-kubeadm-kubernetes/certs/rootCA.crt` existe. A raiz `/` respondeu HTTP 403, mas não é
a rota de carga: o `WebsiteUser` em `tests/scenarios/locustfile.py` solicita `/news.php`. Uma
única requisição GET a essa rota respondeu HTTP 200 em 0.026 s, com a CA do cluster. O endpoint
read-only de status do Prometheus respondeu HTTP 200. A pasta `tests/results/` existe e está
gravável, e não havia CSV com os novos nomes `csa_go`.

```sh
if command -v locust >/dev/null 2>&1; then command -v locust; locust --version; else echo 'locust: not installed in PATH'; fi
.venv/bin/locust --version
ls -l vagrant-kubeadm-kubernetes/certs/rootCA.crt
curl -sS -o /dev/null -w 'znn endpoint HTTP %{http_code}\n' --connect-timeout 5 --max-time 10 https://znn.k8s.lab/
curl --cacert vagrant-kubeadm-kubernetes/certs/rootCA.crt -sS -o /dev/null -w '/news.php HTTP %{http_code}, %{time_total}s\n' --connect-timeout 5 --max-time 10 https://znn.k8s.lab/news.php
curl --cacert vagrant-kubeadm-kubernetes/certs/rootCA.crt -sS -o /dev/null -w 'Prometheus status endpoint HTTP %{http_code}, %{time_total}s\n' --connect-timeout 5 --max-time 10 https://prometheus.k8s.lab/api/v1/status/buildinfo
test -d tests/results && test -w tests/results
find tests/results -maxdepth 1 -type f -name '*csa_go*' -print | sort
.venv/bin/python - <<'PY'
import importlib
import sys

sys.path.insert(0, "tests/scenarios")
for module in ("locust", "requests", "numpy", "pandas", "matplotlib", "seaborn"):
    importlib.import_module(module)
import locustfile
print("Locust scenario imports successfully")
PY
```

O cenário importou com Python 3.13.11 e as dependências da virtualenv. O comando `locust`
ausente no `PATH` não é uma dependência em falta: basta ativar `.venv` antes de chamar um dos
scripts. A resposta 403 em `/` não indica falha da rota usada pelo cenário.

Uma iteração de carga Go foi executada após autorização. Os resultados e comandos estão em
[execucao-carga.md](execucao-carga.md). Os registros disponíveis não mostram timeout das etapas
do executor CSA Go. Houve respostas HTTP 504 da aplicação, ciclos sem métrica externa e um erro
de parse após evaluate sem saída; esses eventos são distintos de timeout do processo. Como a
execução não coletou duração de cada etapa, os timeouts Go ainda não foram calibrados, e uma
rodada única sem versões Python/Java comparáveis não permite concluir performance relativa.

## O que os CSVs permitem observar

[extract_prom.py](../../../extract_prom.py#L16) coleta latência, throughput, proporção
de respostas 200, SLO, Pods por tag e limites de CPU do alvo; o cenário Locust acrescenta
seus dados de usuários e respostas.

A série chamada znn_error_rate calcula respostas 200 / total, ou seja, não é diretamente
a fração de erros. A série kube_pod_cpu_limits soma máximos por nome de container:
não é consumo de CPU, nem soma de todos os Pods, nem custo do processo adaptador.
O levantamento não modificou essas consultas.

Esses resultados podem comparar o efeito das estratégias sobre a aplicação. Não bastam
para atribuir uma diferença isoladamente ao custo de CPU/memória da linguagem ou calibrar
a duração de cada etapa. A obtenção dessas durações precisa ser explicitada na etapa de
calibração; não há série correspondente no extrator inspecionado.

Antes da análise futura, registrar versões reais, digests das imagens, recursos e estado
inicial usados pelo usuário. As tags latest e dependências Python não fixadas limitam a
reprodutibilidade a partir do código local sozinho.
