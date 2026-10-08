# Registro dos comandos — investigação da entrega 1

Data desta entrega e da transcrição: 2026-09-23.
Diretório de execução dos comandos, salvo opção explícita `git -C`:
`/home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/autoscalers`.
Revisão: `3e0e43035a8766eafbbdf507bd25d1d238d3fa5d`.

Este registro documenta comandos, fontes, resultados e decisões. Os comandos em blocos
são transcrições, não instruções para executar experimentos. Fontes e conclusões detalhadas
estão em [levantamento](levantamento.md), [melhorias](melhorias-de-performance.md),
[paridade](paridade.md), [testes](testes-unitarios.md) e [integração](integracao-e-timeouts.md).
Não houve pesquisa web nesta investigação. Resultados extensos de código não são duplicados
aqui; algumas saídas foram truncadas pela ferramenta e complementadas com leituras dirigidas.

## Comandos desta entrega

### E01 — Inventário e instruções

```bash
pwd && git status --short && rg --files --hidden csa-go -g '!bin/**' -g '!**/.git/**' && cat ../AGENTS.md && rg --files --hidden . -g AGENTS.md -g '!**/.git/**'
```

Resultado: código 1. Inventário do scaffold Go, referências e instruções. O último rg não encontrou AGENTS.md abaixo de autoscalers e encerrou com código 1; o guia aplicável foi lido em ../AGENTS.md.

### E02 — Revisões e preservação das referências

```bash
python3 - <<'PY'
import hashlib, json, pathlib, subprocess
root = pathlib.Path.cwd()
paths = subprocess.check_output(['git', 'ls-files', '--', 'csa', 'csa-java'], text=True).splitlines()
checksums = {path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in paths}
print(json.dumps({'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(), 'files': checksums}, indent=2))
for repo in ['../../custom-self-adapter', '../../custom-self-adapter-operator']:
    result = subprocess.run(['git', '-C', repo, 'rev-parse', 'HEAD'], text=True, capture_output=True)
    print(repo, result.returncode, result.stdout.strip())
PY
```

Resultado: código 0. Registrada revisão do laboratório e SHA-256 de cada arquivo versionado Python/Java. Revisões dos repositórios locais de runtime/operador também registradas; comparação final ao término.

### E03 — Referência Python: inicialização, métrica e avaliação

```bash
cat ../../AGENTS.md && nl -ba csa/scripts/metric_nginx_req_duration.py && nl -ba csa/scripts/evaluate.py && nl -ba csa/scripts/initial_data.py && nl -ba csa/scripts/adapter_logger.py
```

Resultado: código 0. Leitura estática de inicialização, métrica, avaliação, estado inicial e logs Python. Evidências em melhorias e paridade.

### E04 — Referência Python: acesso ao Kubernetes e adaptações

```bash
nl -ba csa/scripts/adapt_base.py && nl -ba csa/scripts/adapt_cpu.py && nl -ba csa/scripts/adapt_tag.py && nl -ba csa/scripts/adapt_replicas.py
```

Resultado: código 0. Leitura estática das chamadas Kubernetes, rollout, seleção de CPU, resize e patches Python. Sem execução desses scripts.

### E05 — Java: processo, configuração, logging e serialização

```bash
nl -ba csa-java/src/main/java/org/csajava/App.java && nl -ba csa-java/src/main/java/org/csajava/context/RuntimeContext.java && nl -ba csa-java/src/main/java/org/csajava/config/ConfigLoader.java && nl -ba csa-java/src/main/java/org/csajava/logging/AdapterLogger.java && nl -ba csa-java/src/main/java/org/csajava/kubernetes/KubernetesJson.java && nl -ba csa-java/src/main/java/org/csajava/io/JsonOut.java
```

Resultado: código 0. Leitura estática de inicialização, YAML, logs e serialização Java. Saída extensa complementada pelo código disponível e leituras dirigidas.

### E06 — Java: avaliação e persistência de estado

```bash
nl -ba csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java && nl -ba csa-java/src/main/java/org/csajava/runtime/initialdata/InitialDataStore.java
```

Resultado: código 0. Leitura estática da avaliação e do contrato initialData Java. Casos de zero inicial e reconciliação anotados.

### E07 — Java: adaptações e diferenças de conversão

```bash
rg -n 'runningPods|currentMcpu|readNamespaced|patchNamespaced|roundedMcpu|Math.rint|loadConfig|KubernetesJson|rolloutInProgress|new InitialDataStore|new AdapterLogger|new ApiClient|new CoreV1Api|new CustomObjectsApi' csa-java/src/main/java && nl -ba csa-java/src/main/java/org/csajava/util/CpuQuantity.java && nl -ba csa-java/src/main/java/org/csajava/util/JsonUtil.java && nl -ba csa-java/src/main/java/org/csajava/runtime/adapt/tag/AdaptTagRuntime.java | sed -n '130,265p' && nl -ba csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java | sed -n '306,347p'
```

Resultado: código 0. Busca dos pontos de integração Java e diferenças de parsing/CPU/tag. Sem testar nem corrigir divergências.

### E08 — Versões declaradas, empacotamento e configuração

```bash
cat csa/requirements.txt csa/Dockerfile csa-java/build.gradle csa-java/settings.gradle csa-java/Dockerfile csa-java/gradle/wrapper/gradle-wrapper.properties csa-go/go.mod csa-go/mise.toml && rg -n 'timeout:|interval:|minReplicas:|maxReplicas:|maxCPU:|enabled_strategies:|adapt_replicas$|adapt_cpu$|adapt_tag$|logVerbosity:|value: 1000|requireKubernetesMetrics' csa/config.yaml csa-java/config.yaml csa-java/profiles && nl -ba csa/build_csa-python.sh && nl -ba csa-java/build_csa-java.sh && cat csa/custom-selfadapter-template.yaml
```

Resultado: código 0. Versões declaradas e configurações registradas em levantamento e integração. Não comprovam versões instaladas ou digests em execução.

### E09 — Testes existentes: evidências estáticas, sem execução

```bash
rg --files csa csa-java/src/test csa-go -g '*Test.java' -g '*test*.py' -g '*_test.go' -g '*.json' && rg -n '@Test|public void' csa-java/src/test/java && nl -ba csa-java/src/test/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntimeTest.java | sed -n '28,87p' && nl -ba csa-java/src/test/java/org/csajava/runtime/initialdata/InitialDataStoreTest.java | sed -n '94,154p'
```

Resultado: código 0. Inventário dos testes Java e leitura de asserções; não é resultado de execução de suítes. Nenhum teste Python/Go encontrado pelos padrões pesquisados nessas pastas.

### E10 — Runtime e operador: processos, timeouts e reconciliação

```bash
nl -ba ../../custom-self-adapter/internal/execute/shell/shell.go | sed -n '40,110p' && nl -ba ../../custom-self-adapter/internal/evaluatecalc/evaluatecalc.go | sed -n '68,88p' && nl -ba ../../custom-self-adapter-operator/internal/reconcile/reconcile.go | sed -n '324,345p' && git -C ../../custom-self-adapter status --short && git -C ../../custom-self-adapter-operator status --short
```

Resultado: código 0. Leitura do executor e reconciliador, com oportunidade P12 e contrato assíncrono registrados. O status combinado incluiu alteração preexistente em Makefile; nenhum arquivo desses repositórios foi escrito.

### E11 — Experimentos existentes e limites da coleta

```bash
rg --files --hidden .. -g 'run_tests*' -g '!**/.git/**' && nl -ba ../run_tests.sh | sed -n '120,185p' && nl -ba ../run_tests_by_cenary.sh | sed -n '285,324p' && rg -n 'PROM_EXTRACT_NAME|custom-selfadapter' ../run_tests_csa.sh && nl -ba ../extract_prom.py | sed -n '14,94p'
```

Resultado: código 0. Três run_tests* localizados; diferenças de limpeza entre cenários e limites das métricas do extrator registrados. Não executados.

### E12 — Complemento de leitura: pontos cortados pela saída anterior

```bash
nl -ba csa/scripts/initial_data.py | sed -n '12,112p' && nl -ba csa/scripts/evaluate.py | sed -n '40,108p' && cat csa-java/src/main/java/org/csajava/util/CpuQuantity.java && nl -ba csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java | sed -n '317,347p' && cat csa/requirements.txt csa-java/build.gradle csa-java/Dockerfile csa-java/gradle/wrapper/gradle-wrapper.properties csa-go/go.mod
```

Resultado: código 0. Leituras menores para completar trechos cortados nas saídas extensas: estado inicial, CPU inicial, helpers e dependências.

## Levantamento anterior — transcrição do histórico disponível

Estes comandos pertencem à discussão anterior do plano, não são execuções novas.
O histórico não fornece um timestamp individual confiável para cada chamada; não foram
inventados horários nem versões de ferramentas executadas. As revisões e versões atuais
estão no levantamento desta entrega. As conclusões do planejamento foram revisadas por
leitura nesta entrega; não tratá-las como medições.

H01–H21 cobrem referências, dependências, runtime/operador, testes existentes e coleta.
H22–H24 correspondem à investigação dos scripts run_tests* quando sua integração foi
adicionada ao plano. Todos são comandos de leitura, inclusive os que consultam executáveis
com command -v. Saídas extensas foram parcialmente truncadas no histórico.

### H01

```bash
git status --short && rg --files -g AGENTS.md -g '*.go' -g '*.java' -g '*.py' -g '*.yaml' -g '*.sh' -g '*.md' -g '*.gradle' -g 'Dockerfile' -g 'go.mod' -g 'mise.toml' csa csa-java csa-go
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H02

```bash
cat ../AGENTS.md && rg --files ../docs ../contracts ../tests . -g '*parity*' -g '*contract*' -g '*performance*' -g 'AGENTS.md' -g '*test*.sh' -g '*test*.py' -g '*scenario*' -g '*.json'
```

Resultado/contexto: Código 2: ../docs e ../contracts não existiam. A busca também retornou arquivos dos demais caminhos. Não interpretar a ausência desses diretórios como ausência de todos os testes.

### H03

```bash
cat csa/scripts/metric_nginx_req_duration.py csa/scripts/evaluate.py
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H04

```bash
cat csa/scripts/adapt_base.py csa/scripts/adapt_replicas.py csa/scripts/adapt_cpu.py
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H05

```bash
cat csa/scripts/adapt_tag.py csa/scripts/initial_data.py csa/scripts/adapter_logger.py
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H06

```bash
cat csa-java/src/main/java/org/csajava/App.java csa-java/src/main/java/org/csajava/cli/Cli.java csa-java/src/main/java/org/csajava/context/RuntimeContext.java csa-java/src/main/java/org/csajava/config/ConfigLoader.java csa-java/src/main/java/org/csajava/runtime/metric/MetricRuntime.java
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H07

```bash
cat csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H08

```bash
cat csa-java/src/main/java/org/csajava/runtime/adapt/AdaptSupport.java csa-java/src/main/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntime.java csa-java/src/main/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntime.java
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H09

```bash
cat csa-java/src/main/java/org/csajava/runtime/initialdata/InitialDataStore.java csa-java/src/main/java/org/csajava/runtime/adapt/tag/AdaptTagRuntime.java
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H10

```bash
cat csa-java/src/main/java/org/csajava/kubernetes/KubernetesClient.java csa-java/src/main/java/org/csajava/kubernetes/KubernetesJson.java csa-java/src/main/java/org/csajava/logging/AdapterLogger.java csa-java/src/main/java/org/csajava/util/CpuQuantity.java csa-java/src/main/java/org/csajava/io/JsonOut.java csa-java/src/main/java/org/csajava/runtime/ModeHandlers.java
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H11

```bash
cat csa-java/src/main/java/org/csajava/runtime/initialdata/InitialDataStore.java && sed -n '100,310p' csa-java/src/main/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntime.java && sed -n '90,180p' csa-java/src/main/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntime.java
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H12

```bash
cat csa/config.yaml csa-java/profiles/h.yaml csa-java/profiles/vq.yaml csa-java/Dockerfile csa-java/build_csa-java.sh csa/Dockerfile csa/build_csa-python.sh csa-java/custom-selfadapter-h.yaml csa-go/main.go && sed -n '1,240p' ../run_tests.sh
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H13

```bash
rg -n 'HttpServer|createContext|@Test|public void|trace|hints|initialData|resize|request' csa-java/src/test/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntimeTest.java csa-java/src/test/java/org/csajava/runtime/initialdata/InitialDataStoreTest.java csa-java/src/test/java/org/csajava/AppTest.java && rg --files ../../ -g '*shell*.go' -g '*config*.go' -g '*initial*.go' -g 'AGENTS.md' -g 'go.mod' -g '*benchmark*' -g '*scenario*' -g 'run_tests.sh' -g '*.md'
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H14

```bash
cat ../../AGENTS.md ../../custom-self-adapter/internal/execute/shell/shell.go && rg -n 'Timeout|timeout|time.Millisecond|exec.Command|InitialData|initialData|logStderr|LogStderr' ../../custom-self-adapter/internal ../../custom-self-adapter-operator/internal -g '*.go'
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H15

```bash
rg -n 'csa|duration|cpu|memory|PROM_EXTRACT_NAME|prometheus|latency|adapt' ../tests/scenarios/locustfile.py ../tests -g '*.py' -g '!**/results/**' -g '!**/notebooks/**' && cat csa-java/src/test/java/org/csajava/config/BuildProfileTest.java csa-java/src/test/java/org/csajava/config/ConfigTest.java && cat csa-go/go.mod csa-go/mise.toml
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H16

```bash
rg -n '@Test|public void' csa-java/src/test/java/org/csajava/runtime/evaluate/EvaluateQuantityTest.java csa-java/src/test/java/org/csajava/runtime/adapt/tag/AdaptTagRuntimeTest.java csa-java/src/test/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntimeTest.java && rg --files csa-java -g '*gradlew*' -g 'AGENTS.md' -g '*.json' && rg --files ../../custom-self-adapter/internal
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H17

```bash
cat ../../custom-self-adapter/internal/api/v1/api.go ../../custom-self-adapter/internal/evaluatecalc/evaluatecalc.go ../../custom-self-adapter/internal/adapting/adapting.go && sed -n '1,230p' ../../custom-self-adapter/internal/selfadapter/selfadapter.go
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H18

```bash
cat ../tests/scenarios/locustfile.py && rg -n 'duration|cpu|memory|csa|quantile' ../prom*.py ../*extract*.py ../tests -g '*.py' -g '!**/results/**' -g '!**/notebooks/**' && cat csa-java/src/test/java/org/csajava/runtime/evaluate/EvaluateQuantityTest.java && sed -n '1,165p' csa-java/src/test/java/org/csajava/runtime/initialdata/InitialDataStoreTest.java
```

Resultado/contexto: Código 2 no rg: ../prom*.py não correspondeu a arquivos. Os comandos posteriores unidos por && não foram executados nessa chamada; leituras complementares aparecem em H20/H21.

### H19

```bash
rg -n 'DefaultQPS|DefaultBurst|RateLimiter|ContentType|Burst' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/config.go && rg -n 'func.*Resize|Patch\(|UseProtobufAsDefault|PrefersProtobuf' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/kubernetes/typed/core/v1/pod*.go && command -v docker && command -v python && command -v java && git rev-parse HEAD && rg --files -g AGENTS.md -g '.gitignore' -g 'mise.toml' csa-go csa-java csa
```

Resultado/contexto: Consultados defaults e transporte no cache local client-go 0.35.3; disponibilidade de docker/python/java e revisão foram pesquisadas. Nenhum desses programas foi usado para executar testes ou builds por esse comando.

### H20

```bash
cat ../../custom-self-adapter/metric/metric.go ../../custom-self-adapter/evaluate/evaluate.go ../../custom-self-adapter/adapt/adapt.go && sed -n '1,150p' ../extract_prom.py && rg -n 'duration|elapsed|Elapsed|Since|executionTime|ExecutionTime|execution_time|time.Now' ../../custom-self-adapter -g '*.go' && sed -n '320,350p' ../../custom-self-adapter-operator/internal/reconcile/reconcile.go
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H21

```bash
cat csa-java/src/test/java/org/csajava/runtime/evaluate/EvaluateQuantityTest.java && sed -n '94,156p' csa-java/src/test/java/org/csajava/runtime/initialdata/InitialDataStoreTest.java && sed -n '360,388p' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/config.go && sed -n '180,250p' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/config.go && cat csa/custom-selfadapter-template.yaml
```

Resultado/contexto: Leitura/pesquisa de fontes locais usada no planejamento; conclusões revisadas nos documentos desta entrega. Nenhum teste ou carga executado.

### H22

```bash
rg --files --hidden .. -g 'run_tests*' -g '!**/.git/**'
```

Resultado/contexto: Identificados e lidos run_tests.sh, run_tests_csa.sh e run_tests_by_cenary.sh. A integração futura inclui os três; nenhuma matriz executada.

### H23

```bash
rg -n 'csa|CASE|case |usage|Usage|PROM_EXTRACT_NAME|run_test|ITERATION|SCENARIO|CENARY' ../run_tests_csa.sh ../run_tests_by_cenary.sh ../run_tests.sh
```

Resultado/contexto: Identificados e lidos run_tests.sh, run_tests_csa.sh e run_tests_by_cenary.sh. A integração futura inclui os três; nenhuma matriz executada.

### H24

```bash
sed -n '1,115p' ../run_tests_csa.sh && sed -n '1,130p' ../run_tests_by_cenary.sh && tail -n 70 ../run_tests_by_cenary.sh && tail -n 45 ../run_tests_csa.sh
```

Resultado/contexto: Identificados e lidos run_tests.sh, run_tests_csa.sh e run_tests_by_cenary.sh. A integração futura inclui os três; nenhuma matriz executada.

## Operações de documentação

A ferramenta apply_patch criou levantamento.md, melhorias-de-performance.md, paridade.md,
testes-unitarios.md, integracao-e-timeouts.md e este registro em csa-go/docs/.
Não foi usado comando shell para escrever esses arquivos. A orquestração com functions.exec
apenas reuniu resultados e gerou texto; não executou os comandos históricos novamente.
As revisões posteriores deste conjunto se limitam a corrigir a documentação e registrar
o resultado das verificações abaixo.

## Verificação documental

Antes da execução, uma tentativa de montar os comandos em functions.exec falhou por
SyntaxError (delimitador do JavaScript). Nenhum comando shell dessa tentativa chegou a
executar. A orquestração foi corrigida e as verificações abaixo foram concluídas.

### V01 — Links locais e whitespace da documentação

```bash
python3 - <<'PY'
import pathlib, re, sys
docs = pathlib.Path('csa-go/docs')
errors, links = [], 0
files = sorted(docs.glob('*.md'))
for doc in files:
    body = doc.read_text()
    for number, line in enumerate(body.splitlines(), 1):
        if line != line.rstrip():
            errors.append(f'{doc}:{number}: whitespace ao final')
    if not body.endswith('\n'):
        errors.append(f'{doc}: newline final ausente')
    fence = chr(96) * 3
    prose = re.sub(r'^' + fence + r'[^\n]*\n.*?^' + fence + r'[^\n]*$', '', body, flags=re.M | re.S)
    for target in re.findall(r'\[[^\]\n]+\]\(([^)\n]+)\)', prose):
        if '://' in target:
            continue
        path, _, fragment = target.partition('#')
        resolved = doc.parent / path if path else doc
        links += 1
        if not resolved.exists():
            errors.append(f'{doc}: destino inexistente: {target}')
        elif fragment.startswith('L') and fragment[1:].isdigit():
            if not resolved.is_file() or not 1 <= int(fragment[1:]) <= len(resolved.read_text().splitlines()):
                errors.append(f'{doc}: linha inexistente: {target}')
print(f'documentos={len(files)} links={links} erros={len(errors)}')
for error in errors:
    print(error)
sys.exit(bool(errors))
PY
```

Resultado: Primeira execução: documentos=6 links=105 erros=0; código 0. Destinos locais e limites das linhas citadas existem; nenhum whitespace ao final ou newline ausente. Esta verificação não substitui a revisão semântica das conclusões.

### V02 — Integridade final dos arquivos Python/Java

```bash
python3 - <<'PY'
import hashlib, json, pathlib, subprocess
root = pathlib.Path.cwd()
paths = subprocess.check_output(['git', 'ls-files', '--', 'csa', 'csa-java'], text=True).splitlines()
checksums = {path: hashlib.sha256((root / path).read_bytes()).hexdigest() for path in paths}
print(json.dumps({'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(), 'files': checksums}, indent=2))
for repo in ['../../custom-self-adapter', '../../custom-self-adapter-operator']:
    result = subprocess.run(['git', '-C', repo, 'rev-parse', 'HEAD'], text=True, capture_output=True)
    print(repo, result.returncode, result.stdout.strip())
PY
```

Resultado: Os mapas SHA-256 obtidos em E02 e V02 foram comparados por caminho na orquestração functions.exec: 76 arquivos, nenhuma diferença, mesma revisão. O comando apenas lê arquivos e consulta Git.

### V03 — Diff e estado final do workspace

```bash
git diff --exit-code HEAD -- csa csa-java && git diff --check && git status --short
```

Resultado: Código 0. Nenhum diff em csa/ e csa-java/ contra HEAD; git diff --check aprovado. Status: alteração preexistente em ../AGENTS.md, submódulo de infraestrutura modificado e csa-go/ não rastreado. O diff --check não cobre arquivos não rastreados; V01 verifica whitespace dos novos documentos.

Após consolidar este registro e marcar a entrega como concluída, V01 foi repetido
com o mesmo comando: código 0, documentos=6 links=105 erros=0. Essa repetição é uma
verificação documental, não um teste de comportamento do CSA.

## Entrega 2 — contratos consumidos pelo Go

Esta entrega cria somente os JSONs de contrato compartilhados e os testes Go que os consomem.

### E2-01 — inspeção das adaptações Python

```bash
nl -ba csa/scripts/adapt_cpu.py
```

Resultado: leitura da seleção de CPU, limites, caminho de resize, ordem das chamadas e
interrupção na primeira falha. Usado para documentar casos que as entregas Go cobrirão.

```bash
nl -ba csa/scripts/adapt_replicas.py
```

Resultado: leitura do parâmetro de réplicas e do patch do Deployment carregado.

```bash
nl -ba csa/scripts/adapt_tag.py
```

Resultado: leitura da escada de tags, baseline inicial, rollout e update_cpu.

### E2-02 — inspeção das referências Java

```bash
nl -ba csa-java/src/main/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntime.java
```

Resultado: leitura do caminho Java correspondente; nenhum arquivo Java foi editado.

```bash
nl -ba csa-java/src/main/java/org/csajava/util/CpuQuantity.java && nl -ba csa-java/src/main/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntime.java | sed -n '150,310p'
```

Resultado: inspeção dos limites de conversão, arredondamento de CPU e caminho de resize.

```bash
nl -ba csa-java/src/main/java/org/csajava/runtime/adapt/tag/AdaptTagRuntime.java | sed -n '120,260p'
```

Resultado: inspeção do comportamento de tag para manter as diferenças documentadas.

### E2-03 — validação estrutural do corpus

```bash
python3 - <<'PY'
import json
from collections import Counter
from pathlib import Path

root = Path("contracts/cases")
index = json.loads((root / "index.json").read_text())
ids = index["cases"]
errors = []
if len(ids) != len(set(ids)):
    errors.append("IDs duplicados no índice")
operations = Counter()
required = {"id", "operation", "input", "config", "state", "expectedResult"}
for case_id in ids:
    path = root / f"{case_id}.json"
    try:
        case = json.loads(path.read_text())
    except Exception as error:
        errors.append(f"{path}: JSON inválido: {error}")
        continue
    missing = required - case.keys()
    if missing:
        errors.append(f"{path}: campos ausentes: {sorted(missing)}")
    if case.get("id") != case_id:
        errors.append(f"{path}: id difere do índice")
    if not isinstance(case.get("config"), dict) or not isinstance(case.get("state"), dict):
        errors.append(f"{path}: config/state precisam ser objetos")
    if "expectedByImplementation" in case:
        errors.append(f"{path}: resultado específico de outra linguagem")
    operations[case.get("operation", "<ausente>")] += 1
files = {path.stem for path in root.glob("*.json") if path.name != "index.json"}
if files != set(ids):
    errors.append(f"arquivos fora do índice: {sorted(files - set(ids))}; ausentes: {sorted(set(ids) - files)}")
print(f"fixtures={len(ids)} operações={dict(sorted(operations.items()))} erros={len(errors)}")
for error in errors:
    print(error)
raise SystemExit(bool(errors))
PY
```

Resultado: código 0; 16 fixtures, 4 cpu_quantity, 10 evaluate e 2 metric; nenhum erro.
É uma verificação de estrutura JSON, sem executar lógica de referência nem a futura lógica Go.

```bash
git status --short -- csa csa-java && rg --files csa csa-java | rg '__pycache__|\\.pyc$'
```

Resultado: nenhum diff versionado em csa/ ou csa-java/. A busca também encontrou
parity/__pycache__ preexistente em csa-java; não foi alterado.

### E2-04 — revisão final de contratos, links e documentação

```bash
python3 - <<'PY'
import json, re, sys
from collections import Counter
from pathlib import Path

case_root = Path("contracts/cases")
index = json.loads((case_root / "index.json").read_text())
ids = index["cases"]
errors, operations = [], Counter()
required = {"id", "operation", "input", "config", "state", "expectedResult"}
for case_id in ids:
    path = case_root / f"{case_id}.json"
    case = json.loads(path.read_text())
    if not required.issubset(case):
        errors.append(f"{path}: campos obrigatórios ausentes")
    if case.get("id") != case_id:
        errors.append(f"{path}: id difere do índice")
    if "expectedByImplementation" in case:
        errors.append(f"{path}: resultado específico de outra linguagem")
    operations[case.get("operation", "<ausente>")] += 1
if len(ids) != len(set(ids)):
    errors.append("IDs duplicados")
case_files = {p.stem for p in case_root.glob("*.json") if p.name != "index.json"}
if case_files != set(ids):
    errors.append("índice e arquivos JSON diferem")

doc_paths = sorted(Path("csa-go/docs").glob("*.md"))
links = 0
for doc in doc_paths:
    body = doc.read_text()
    for line_number, line in enumerate(body.splitlines(), 1):
        if line != line.rstrip():
            errors.append(f"{doc}:{line_number}: whitespace final")
    if not body.endswith("\n"):
        errors.append(f"{doc}: newline final ausente")
    fence = chr(96) * 3
    prose = re.sub(r"^" + fence + r"[^\n]*\n.*?^" + fence + r"[^\n]*$", "", body, flags=re.M | re.S)
    for target in re.findall(r"\[[^\]\n]+\]\(([^)\n]+)\)", prose):
        if "://" in target:
            continue
        path, _, fragment = target.partition("#")
        resolved = doc.parent / path if path else doc
        links += 1
        if not resolved.exists():
            errors.append(f"{doc}: destino inexistente {target}")
        elif fragment.startswith("L") and fragment[1:].isdigit():
            if not resolved.is_file() or not 1 <= int(fragment[1:]) <= len(resolved.read_text().splitlines()):
                errors.append(f"{doc}: linha inexistente {target}")
print(f"fixtures={len(ids)} operações={dict(sorted(operations.items()))} documentos={len(doc_paths)} links={links} erros={len(errors)}")
for error in errors:
    print(error)
raise SystemExit(bool(errors))
PY
```

Resultado: código 0; 16 contratos, 6 documentos, 107 links válidos e nenhum erro de
estrutura, whitespace, destino ou referência de linha.

## Comandos da entrega 3 — entrada/saída, configuração, logs e métrica

Data: 2026-09-23. Diretório dos comandos Go: `autoscalers/csa-go/`; demais comandos em
`autoscalers/`. Revisão-base: `3e0e43035a8766eafbbdf507bd25d1d238d3fa5d`.

### D3-01 — inspeção dos contratos de execução

```bash
cat csa/config.yaml && sed -n '1,220p' csa/scripts/adapt_base.py && sed -n '1,140p' csa/scripts/metric_nginx_req_duration.py && sed -n '1,180p' csa-java/src/main/java/org/csajava/config/ConfigLoader.java
```

Resultado: código 0. Confirmados os valores do config Python, a leitura da configuração,
o comportamento da métrica (primeiro item e tipos preservados) e os diagnósticos de YAML.
Nenhum arquivo de referência foi executado ou alterado.

### D3-02 — TDD da métrica e CLI

```bash
GOCACHE=/tmp/csa-go-gocache go test ./...
```

Resultado antes da implementação: código 1; os dois contratos de métrica tinham stdout
vazio e os casos de argumentos/JSON inválidos retornavam código 0. A primeira tentativa
`go test ./...`, sem GOCACHE explícito, não conseguiu gravar em
`/home/rodrigo/.cache/go-build` por permissão do ambiente; as execuções seguintes usaram
`/tmp/csa-go-gocache`.

### D3-03 — TDD do carregador YAML

```bash
GOCACHE=/tmp/csa-go-gocache go test ./...
```

Resultado após adicionar testes e antes de criar `loadConfig`: código 1; build dos testes
falhou porque a função ainda não existia. Depois de uma primeira implementação com
`sigs.k8s.io/yaml`, a mesma suíte falhou porque inteiros YAML chegaram como `float64`.
Foi então usado `go.yaml.in/yaml/v2`, que mantém os parâmetros numéricos como `int`, como
esperado pela configuração consumida em Go. Com a mudança, os testes passaram.

### D3-04 — formatação e primeira suíte verde

```bash
gofmt -w main.go logger.go metric.go config.go main_test.go config_test.go
GOCACHE=/tmp/csa-go-gocache go test ./...
```

Resultado: formatação código 0; testes código 0. O teste de métrica também confirma que
um caminho `/config.yaml` inexistente não afeta o modo `metric`, e que o evento é gravado
em stderr e em arquivo temporário.

### D3-05 — RED de regressão da métrica

```bash
GOCACHE=/tmp/csa-go-gocache go test ./... -run '^TestMetricContractFixtures$' -count=1
```

Resultado: código 1 após retirar temporariamente a implementação da métrica; ambos os
fixtures falharam com erro de execução, confirmando que os testes detectam regressão.
Implementação restaurada em seguida.

### D3-06 — RED de regressão da configuração

```bash
GOCACHE=/tmp/csa-go-gocache go test ./... -run '^TestLoad' -count=1
```

Resultado: código 1 após retirar temporariamente o carregador; os casos de configuração
válida, YAML vazio/nulo e YAML malformado falharam. O teste de arquivo ausente continuou
passando porque qualquer erro de leitura já é o resultado esperado. Implementação restaurada.

### D3-07 — verificação final

```bash
gofmt -w main.go logger.go metric.go config.go main_test.go config_test.go
GOCACHE=/tmp/csa-go-gocache go test ./... -count=1
GOCACHE=/tmp/csa-go-gocache go build -o /tmp/csa-go .
```

Resultado: todos os comandos com código 0; a suíte rodou sem cache de resultado e o
executável compilou em `/tmp/csa-go`. Não houve execução de cluster ou carga. A suíte Go
usa somente biblioteca padrão e os módulos já presentes no cache local.

### D3-08 — formatação, contratos, documentos e referências protegidas

Diretório `autoscalers/csa-go/`:

```bash
gofmt -d main.go logger.go metric.go config.go main_test.go config_test.go
```

Diretório `autoscalers/`:

```bash
python3 - <<'PY'
import json, pathlib, re
from collections import Counter
root = pathlib.Path.cwd()
case_root = root / 'contracts/cases'
index = json.loads((case_root / 'index.json').read_text())
ids = index['cases']
errors, operations = [], Counter()
required = {'id', 'operation', 'input', 'config', 'state', 'expectedResult'}
for case_id in ids:
    path = case_root / f'{case_id}.json'
    case = json.loads(path.read_text())
    if not required.issubset(case): errors.append(f'{path}: missing fields')
    if case.get('id') != case_id: errors.append(f'{path}: ID differs from index')
    if 'expectedByImplementation' in case: errors.append(f'{path}: implementation-specific result')
    operations[case.get('operation', '<missing>')] += 1
if len(ids) != len(set(ids)): errors.append('duplicate IDs')
case_files = {p.stem for p in case_root.glob('*.json') if p.name != 'index.json'}
if case_files != set(ids): errors.append('index and files differ')
docs = sorted((root / 'csa-go/docs').glob('*.md'))
links = 0
for doc in docs:
    body = doc.read_text()
    for n, line in enumerate(body.splitlines(), 1):
        if line != line.rstrip(): errors.append(f'{doc}:{n}: trailing whitespace')
    if not body.endswith('\n'): errors.append(f'{doc}: missing final newline')
    prose = re.sub(r'^```[^\n]*\n.*?^```[^\n]*$', '', body, flags=re.M | re.S)
    for target in re.findall(r'\[[^\]\n]+\]\(([^)\n]+)\)', prose):
        if '://' in target: continue
        path, _, fragment = target.partition('#')
        resolved = doc.parent / path if path else doc
        links += 1
        if not resolved.exists(): errors.append(f'{doc}: missing target {target}')
        elif fragment.startswith('L') and fragment[1:].isdigit() and (not resolved.is_file() or not 1 <= int(fragment[1:]) <= len(resolved.read_text().splitlines())): errors.append(f'{doc}: missing line {target}')
print(f'fixtures={len(ids)} operations={dict(sorted(operations.items()))} docs={len(docs)} links={links} errors={len(errors)}')
for error in errors: print(error)
raise SystemExit(bool(errors))
PY
```

```bash
git diff --exit-code HEAD -- csa csa-java
```

Resultado: `gofmt -d` sem diferenças; validador executado nas edições finais, inclusive
após D3-09, código 0 com 16 fixtures, 6 documentos, 107 links e zero erros; diff
Python/Java vazio (código 0). Nenhum arquivo das referências foi alterado.

### D3-09 — busca por estados antigos da entrega

```bash
rg -n 'aguardando solicitação da entrega 3|não iniciada|não foram iniciados|ainda não estão|serão adicionados junto|seis documentos|107 links' csa-go/README.md csa-go/docs
```

Resultado: código 0. As ocorrências restantes são declarações históricas do resultado da
entrega 1/2 ou descrevem as entregas 4 e seguintes; não há estado atual indicando que a
entrega 3 esteja pendente.

## Organização do projeto Go — ajuste solicitado após a entrega 3

Data: 2026-09-23. O objetivo foi separar o ponto de entrada e agrupar a implementação e
seus testes, sem alterar comportamento. As operações abaixo ocorreram no diretório
`autoscalers/csa-go/`, salvo a busca de caminhos e as verificações marcadas `autoscalers/`.

### O01 — estado e testes antes da reorganização

```bash
GOCACHE=/tmp/csa-go-gocache go test ./... -count=1
```

Resultado: código 0. A suíte passou antes da movimentação dos arquivos.

Diretório `autoscalers/`:

```bash
rg --files -g '*.go' -g 'go.mod' -g 'go.sum' -g 'README.md' -g '*.md' csa-go && sed -n '1,120p' csa-go/main.go && sed -n '1,100p' csa-go/main_test.go && cat csa-go/go.mod
git status --short
```

Resultado: inventário confirmou fontes e testes Go na raiz de `csa-go/`. O status mostrou
as mesmas alterações preexistentes em `../AGENTS.md`, no submódulo de infraestrutura e
os diretórios não rastreados `contracts/` e `csa-go/`.

### O02 — criação da estrutura e movimentação dos arquivos

```bash
mkdir -p cmd/csa-go internal/csa
mv main.go internal/csa/app.go
mv config.go internal/csa/config.go
mv config_test.go internal/csa/config_test.go
mv logger.go internal/csa/logger.go
mv metric.go internal/csa/metric.go
mv main_test.go internal/csa/app_test.go
```

Resultado: todos os comandos terminaram com código 0. O executável passou a ter um ponto
de entrada em `cmd/csa-go/main.go`; implementação e testes ficaram em `internal/csa/`.
Os testes foram atualizados para localizar os contratos em `../../../contracts/cases`.

### O03 — verificações após a movimentação

```bash
gofmt -w cmd/csa-go/main.go internal/csa/app.go internal/csa/config.go internal/csa/config_test.go internal/csa/logger.go internal/csa/metric.go internal/csa/app_test.go
GOCACHE=/tmp/csa-go-gocache go test ./... -count=1
GOCACHE=/tmp/csa-go-gocache go build -o /tmp/csa-go ./cmd/csa-go
GOCACHE=/tmp/csa-go-gocache go list ./...
gofmt -d cmd/csa-go/main.go internal/csa/app.go internal/csa/config.go internal/csa/config_test.go internal/csa/logger.go internal/csa/metric.go internal/csa/app_test.go
```

Resultado: formatação, testes, build e listagem de pacotes passaram. O primeiro `go list`
sem `GOCACHE` falhou ao tentar gravar no cache padrão fora da área gravável; repetido com
`GOCACHE=/tmp/csa-go-gocache`, passou e listou somente `cmd/csa-go` e `internal/csa`.
Uma chamada inicial, executada em `autoscalers/`, falhou porque os caminhos eram relativos
a `csa-go/` e o `rg` depois de `&&` não chegou a rodar:

```bash
gofmt -d cmd/csa-go/main.go internal/csa/app.go internal/csa/config.go internal/csa/config_test.go internal/csa/logger.go internal/csa/metric.go internal/csa/app_test.go && rg --files -g '*.go' csa-go
```

Repeti `gofmt -d` em `csa-go/`; não reportou diferenças.

Diretório `autoscalers/`:

```bash
rg --files -g '*.go' csa-go
git diff --exit-code HEAD -- csa csa-java
```

Resultado: os sete arquivos Go aparecem somente sob `cmd/csa-go/` e `internal/csa/`;
`csa/` e `csa-java/` continuam sem diferenças contra HEAD. Nenhuma lógica foi alterada.

### O04 — validação final dos documentos

Diretório `autoscalers/`:

```bash
python3 - <<'PY'
import pathlib, re
root = pathlib.Path.cwd()
files = [root / 'csa-go/README.md', *sorted((root / 'csa-go/docs').glob('*.md'))]
errors = []
links = 0
for doc in files:
    body = doc.read_text()
    if len(re.findall(r'^```', body, flags=re.M)) % 2:
        errors.append(f'{doc}: unclosed code fence')
    for n, line in enumerate(body.splitlines(), 1):
        if line != line.rstrip():
            errors.append(f'{doc}:{n}: trailing whitespace')
    prose = re.sub(r'^```[^\n]*\n.*?^```[^\n]*$', '', body, flags=re.M | re.S)
    for target in re.findall(r'\[[^\]\n]+\]\(([^)\n]+)\)', prose):
        if '://' in target:
            continue
        path, _, fragment = target.partition('#')
        resolved = doc.parent / path if path else doc
        links += 1
        if not resolved.exists():
            errors.append(f'{doc}: missing target {target}')
        elif fragment.startswith('L') and fragment[1:].isdigit() and not 1 <= int(fragment[1:]) <= len(resolved.read_text().splitlines()):
            errors.append(f'{doc}: missing line {target}')
print(f'docs={len(files)} links={links} errors={len(errors)}')
for error in errors:
    print(error)
raise SystemExit(bool(errors))
PY
```

Resultado: código 0; README e seis documentos verificados, 109 links e zero erros.

### O05 — separação dos testes da métrica

```bash
gofmt -w internal/csa/app_test.go internal/csa/metric_test.go
GOCACHE=/tmp/csa-go-gocache go test ./... -count=1
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestMetricContractFixtures$' -count=1
```

Resultado: todos os comandos com código 0. Os fixtures agora chamam `metricResult`
diretamente em `metric_test.go`; `app_test.go` mantém os testes de execução pela CLI.
Nenhum código de produção foi alterado nesta separação.

## Entrega 4 — Kubernetes, estado inicial e avaliação

Data: 2026-09-24. A revisão do laboratório continua `3e0e43035a8766eafbbdf507bd25d1d238d3fa5d`.
Os testes e builds Go foram executados dentro de `autoscalers/csa-go`; as inspeções das
referências e verificações de documentação foram executadas em `autoscalers/`.

### D04-A — leitura do fluxo de referência e contratos

```bash
rg --files csa-go/internal/csa csa-go/docs
sed -n '1,240p' csa-go/internal/csa/app.go && sed -n '1,240p' csa-go/internal/csa/evaluate.go && sed -n '1,200p' csa-go/internal/csa/logger.go && cat csa-go/go.mod
sed -n '1,260p' csa/scripts/evaluate.py && sed -n '1,220p' csa/scripts/adapt_base.py && sed -n '1,220p' csa/scripts/initial_data.py
cat csa-go/internal/csa/cpu_quantity.go && sed -n '200,460p' csa-go/internal/csa/evaluate.go && cat csa-go/docs/integracao-e-timeouts.md
rg --files contracts/cases | head -40 && sed -n '1,120p' csa-go/internal/csa/evaluate_test.go && sed -n '1,180p' csa-go/internal/csa/config_test.go
```

Resultado: código 0 para as leituras. A fonte Python confirmou a ordem Deployment → config
→ Pods → estado e as releituras de initialData. Um comando exploratório usou um glob sem
correspondência (`cat contracts/cases/evaluate.*.json`) porque os IDs usam `_`, não `.`;
nenhum arquivo foi alterado por essa tentativa. O diretório correto dos contratos é
`contracts/cases/`, localizado sob `autoscalers/`.

### D04-B — dependências e RED inicial

```bash
go mod edit -require=k8s.io/api@v0.35.3 -require=k8s.io/client-go@v0.35.3 && GOCACHE=/tmp/csa-go-gocache go mod tidy
GOCACHE=/tmp/csa-go-gocache go mod tidy
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestEvaluate|TestCPUQuantity' -count=1
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestKubernetesClient' -count=1
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestKubernetesClient|TestInitialData' -count=1
```

Resultado: a primeira tentativa de `go mod tidy` precisou da verificação de checksums da
rede Go; após autorização do usuário para a operação de dependências, `go mod tidy` terminou
com código 0. O primeiro teste RED de avaliação/CPU falhou por funções ainda inexistentes
(`parseCPUToMilli`, `numberAsInt64`, `evaluateDecision` e `parseQuantityInteger`). Os dois
testes RED de Kubernetes/estado falharam pelos símbolos ainda inexistentes
(`newKubernetesClient`, `initialDataStore` e `initialDataAnnotation`). Essas falhas foram
antes da implementação. O comando em `autoscalers/` que tentou ler
`contracts/cases/evaluate.*.json` terminou sem arquivo correspondente; não foi usado como
entrada de teste.

### D04-C — clientes Kubernetes e persistência

```bash
gofmt -w internal/csa/kubernetes_test.go internal/csa/initial_data_test.go
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestKubernetesClient' -count=1
gofmt -w internal/csa/kubernetes.go internal/csa/initial_data.go internal/csa/evaluate_runtime_test.go internal/csa/initial_data_test.go internal/csa/kubernetes_test.go
gofmt -w internal/csa/*.go
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestKubernetesClient|TestInitialData|TestEvaluate' -count=1
cp internal/csa/initial_data.go /tmp/csa-go-delivery4-initial_data.go
mv internal/csa/initial_data.go /tmp/csa-go-delivery4-initial_data.go.moved
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestInitialDataStoreCPURepeatsReadsAndMergesStatusData$' -count=1
mv /tmp/csa-go-delivery4-initial_data.go.moved internal/csa/initial_data.go
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestInitialDataStoreCPURepeatsReadsAndMergesStatusData$' -count=1
```

Resultado: o teste inicial com `httptest.NewServer` falhou porque o sandbox não permite abrir
socket local. Foi substituído por fakes oficiais de client-go; a suíte final não abre socket
nem chama Kubernetes. A primeira execução após os fakes detectou no teste um recurso Pod sem
label `app=demo`; a fixture foi corrigida para corresponder ao seletor. Depois da correção,
os testes focados passaram. Para confirmar RED após a implementação, `initial_data.go` foi
movido temporariamente para `/tmp`; o teste falhou na compilação pelos símbolos do store
ausentes. O arquivo foi restaurado imediatamente e o mesmo teste passou.

### D04-D — dependências de transporte e verificação final

```bash
rg -n 'DefaultQPS|DefaultBurst|RateLimiter == nil|QPS == 0|retry|Retry' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/config.go /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/request.go
rg -n 'OkHttp|retry|retryOnConnectionFailure|readTimeout|connectTimeout' csa-java/src/main/java csa-java/build.gradle
rg -n 'retries|pool_maxsize|timeout|ApiClient|Configuration' csa/requirements.txt csa/scripts -g '*.py' && python3 -c 'import kubernetes.client, inspect; print(kubernetes.client.__file__); print(kubernetes.client.Configuration.__init__.__doc__)'
cat csa-java/build.gradle && rg -n 'Retry|retryOnConnectionFailure|readTimeout|connectTimeout|new OkHttpClient' csa-java/src/main/java csa-java/build.gradle
cat csa-java/src/main/java/org/csajava/kubernetes/KubernetesClient.java && rg -n 'Timeout|ReadTimeout|ConnectTimeout|retry|Retry|OkHttp' /home/rodrigo/.gradle/caches/modules-2/files-2.1/io.kubernetes/client-java/26.0.0 /home/rodrigo/.gradle/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp -g '*.java' -g '*.kt' -g '*.class' 2>/dev/null
cat csa/requirements.txt
sed -n '350,390p' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/config.go && sed -n '438,470p' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/config.go
sed -n '512,560p' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/kubernetes/clientset.go && sed -n '91,112p' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/dynamic/simple.go
sed -n '770,835p' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/request.go && sed -n '1060,1124p' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/request.go
rg -n -A45 'func InClusterConfig' /home/rodrigo/go/pkg/mod/k8s.io/client-go@v0.35.3/rest/config.go
GOCACHE=/tmp/csa-go-gocache go mod tidy
gofmt -d cmd/csa-go/main.go internal/csa/*.go
GOCACHE=/tmp/csa-go-gocache go test ./... -count=1
GOCACHE=/tmp/csa-go-gocache go build -o /tmp/csa-go ./cmd/csa-go
GOCACHE=/tmp/csa-go-gocache go vet ./...
mise exec -- go version && go version && go env GOTOOLCHAIN
wc -l internal/csa/{app.go,evaluate.go,evaluate_runtime.go,initial_data.go,kubernetes.go}
awk 'NF && $1 !~ /^\\/\\// && $1 != "package" && $1 != "import" && $1 != "}" && $0 !~ /\\{$/ {n++} END{print n}' internal/csa/evaluate.go internal/csa/evaluate_runtime.go internal/csa/initial_data.go internal/csa/kubernetes.go internal/csa/cpu_quantity.go
git diff --exit-code HEAD -- csa csa-java
```

Resultado: a busca client-go encontrou defaults QPS 5/burst 10 e retry; as buscas Java e
Python retornaram código 1 sem correspondências e o trecho Python após `&&` não rodou.
Leituras de `KubernetesClient.java`, `build.gradle` e `requirements.txt` completaram as
fontes: Java usa `Config.fromCluster()` sem parâmetros explícitos no código CSA; Python
declara somente `kubernetes` sem versão. Dentro de `csa-go/`, `mise exec -- go version` e
`go version` informaram `go1.25.14`, com `GOTOOLCHAIN=auto`, compatível com `go 1.25.0` do
`go.mod`. O último `git diff` confirmou que `csa/` e `csa-java/` permanecem sem alterações
versionadas.

Uma verificação inicial `mise exec -- go version`, executada a partir de `autoscalers/`,
mostrou o Go `1.24.6` herdado do diretório pai; repetida dentro de `csa-go/`, selecionou
`1.25.14` pelo `mise.toml` do projeto. Duas consultas anteriores ao resumo da sessão também
falharam por terem resolvido o caminho de `contracts` um nível acima e por usar um diretório
de trabalho duplicando `autoscalers/`; o texto exato dessas duas invocações não foi retido
no histórico compactado, então não foi reconstruído. Nenhuma das tentativas alterou arquivos.

O `README.md` e o `AGENTS.md` foram atualizados para refletir `metric`/`evaluate`, a
estrutura `cmd/csa-go`/`internal/csa`, as dependências em uso e os comandos de build atuais.

### D04-E — links e whitespace da documentação

Diretório: `autoscalers/`.

```bash
python3 - <<'PY'
import pathlib, re
root = pathlib.Path.cwd()
files = [pathlib.Path('csa-go/README.md'), *sorted(pathlib.Path('csa-go/docs').glob('*.md'))]
errors=[]
links=0
for doc in files:
    body=doc.read_text()
    if len(re.findall(r'^```', body, flags=re.M)) % 2:
        errors.append(f'{doc}: unclosed code fence')
    for n,line in enumerate(body.splitlines(),1):
        if line != line.rstrip(): errors.append(f'{doc}:{n}: trailing whitespace')
    prose=re.sub(r'^```[^\n]*\n.*?^```[^\n]*$', '', body, flags=re.M|re.S)
    for target in re.findall(r'\[[^\]\n]+\]\(([^)\n]+)\)', prose):
        if '://' in target: continue
        path,_,fragment=target.partition('#')
        resolved=doc.parent/path if path else doc
        links+=1
        if not resolved.exists(): errors.append(f'{doc}: missing {target}')
        elif fragment.startswith('L') and fragment[1:].isdigit() and not 1 <= int(fragment[1:]) <= len(resolved.read_text().splitlines()): errors.append(f'{doc}: bad line {target}')
print(f'docs={len(files)} links={links} errors={len(errors)}')
for error in errors: print(error)
raise SystemExit(bool(errors))
PY
```

Resultado: código 0; README e seis documentos, 109 links e zero erros. Nenhum cluster,
simulador, script `run_tests*` ou carga Locust foi consultado ou executado.

## Entrega 5A — adaptador de réplicas

Data: 2026-09-24. Diretório de execução: raiz `autoscalers/`.

```bash
rg -n "delivery|Entrega|5\\. Réplicas|adapt_replicas|kubernetesAPI|runWithDependencies|type recordingKubernetesAPI" csa-go/docs csa-go/internal/csa csa-go/README.md ../AGENTS.md csa/adapters/adapt_replicas.py csa/scripts/adapt_replicas.py 2>/dev/null
sed -n '1,180p' csa-go/internal/csa/app.go && sed -n '1,240p' csa-go/internal/csa/kubernetes.go
sed -n '1,280p' csa-go/internal/csa/initial_data_test.go && sed -n '1,260p' csa-go/internal/csa/kubernetes_test.go
cat csa/scripts/adapt_replicas.py && sed -n '1,220p' csa/scripts/adapt_base.py && sed -n '1,200p' csa-go/internal/csa/logger.go
sed -n '42,70p' csa-go/docs/levantamento.md && sed -n '125,160p' csa-go/docs/levantamento.md && cat csa-go/README.md && sed -n '45,115p' csa-go/docs/testes-unitarios.md && sed -n '1,130p' csa-go/internal/csa/evaluate_runtime_test.go && sed -n '130,220p' csa-go/internal/csa/evaluate_runtime_test.go
rg --files contracts/cases && for file in contracts/cases/*; do printf '\n--- %s ---\n' "$file"; cat "$file"; done
rg -n 'func mapField' csa-go/internal/csa && sed -n '270,310p' csa-go/internal/csa/evaluate.go
```

Resultado: leitura estática confirmou que o Python carrega o Deployment antes de validar
`replicas` (aceita `int`, inclusive `bool` pela semântica Python), altera o objeto carregado
e envia o Deployment inteiro em strategic merge patch. Sucesso escreve `{"replicas": ...}`;
falha no patch escreve `{"result":"error"}`; réplica inválida termina sem stdout. O Go já
faz GET dinâmico do Deployment, mas ainda não expõe PATCH de Deployment nem despacha
`adapt_replicas`. Nenhum cluster foi acessado.

### E5A-02 — RED, GREEN, RED e GREEN dos testes Go

Diretório: `autoscalers/csa-go/`. Cache: `/tmp/csa-go-gocache`.

```bash
gofmt -w internal/csa/adapt_replicas_test.go internal/csa/initial_data_test.go internal/csa/kubernetes_test.go && GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestAdaptReplicas|TestKubernetesClientPatchesDeploymentWithStrategicMergeBody' -count=1
```

Resultado RED: código 1; a primeira execução também apontou imports ainda não usados nos
testes, que foram removidos. Repetido após a limpeza, o teste falhou com
`client.patchDeployment undefined`, pois o cliente ainda não tinha PATCH de Deployment.

Após a implementação:

```bash
gofmt -w internal/csa/adapt_replicas_test.go internal/csa/adapt_replicas.go internal/csa/app.go internal/csa/kubernetes.go internal/csa/initial_data_test.go internal/csa/kubernetes_test.go
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestAdaptReplicas|TestKubernetesClientPatchesDeploymentWithStrategicMergeBody' -count=1
```

Resultado GREEN: código 0, testes focados aprovados.

```bash
python3 - <<'PY'
import os, pathlib, subprocess
path = pathlib.Path('internal/csa/adapt_replicas.go')
original = path.read_bytes()
stub = '''package csa\n\nimport "io"\n\nfunc runAdaptReplicas(_ []byte, _, _ io.Writer, _ string, _ func() (kubernetesAPI, error)) int { return 1 }\n'''
env = os.environ.copy()
env['GOCACHE'] = '/tmp/csa-go-gocache'
try:
    path.write_text(stub)
    result = subprocess.run(['go', 'test', './internal/csa', '-run', 'TestAdaptReplicas', '-count=1'], env=env, text=True, capture_output=True)
finally:
    path.write_bytes(original)
print(result.stdout, end='')
print(result.stderr, end='')
print(f'temporary baseline exit code: {result.returncode}')
if result.returncode == 0:
    raise SystemExit('expected RED, tests unexpectedly passed')
PY
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestAdaptReplicas|TestKubernetesClientPatchesDeploymentWithStrategicMergeBody' -count=1
```

Resultado RED/GREEN: com o runtime temporariamente substituído por retorno não implementado,
os testes de adaptação falharam pelas saídas e códigos incorretos (exit 1, stdout vazio).
O arquivo foi restaurado no `finally`; a suíte focada voltou a passar em seguida.

### E5A-03 — verificação final e referências

```bash
GOCACHE=/tmp/csa-go-gocache go test ./... -count=1
GOCACHE=/tmp/csa-go-gocache go vet ./...
GOCACHE=/tmp/csa-go-gocache go build -o /tmp/csa-go ./cmd/csa-go
git diff --exit-code -- csa csa-java
git status --short && git rev-parse HEAD
```

Resultado: teste completo, `go vet`, build e verificação de alterações em `csa/` e
`csa-java/` passaram. A revisão de base era `3e0e43035a8766eafbbdf507bd25d1d238d3fa5d`;
`git status` também mostrou o submódulo `vagrant-kubeadm-kubernetes` já modificado e as
árvores não versionadas `contracts/` e `csa-go/` da implementação em andamento.

```bash
gofmt -d internal/csa/*.go
python3 - <<'PY'
import json, pathlib, re
root = pathlib.Path.cwd()
files = [root / 'csa-go/README.md', *sorted((root / 'csa-go/docs').glob('*.md'))]
errors=[]
links=0
for doc in files:
    body=doc.read_text()
    if len(re.findall(r'^```', body, flags=re.M)) % 2:
        errors.append(f'{doc}: unclosed code fence')
    for line_no,line in enumerate(body.splitlines(),1):
        if line != line.rstrip(): errors.append(f'{doc}:{line_no}: trailing whitespace')
    prose=re.sub(r'^```[^\n]*\n.*?^```[^\n]*$', '', body, flags=re.M|re.S)
    for target in re.findall(r'\[[^\]\n]+\]\(([^)\n]+)\)', prose):
        if '://' in target: continue
        path,_,fragment=target.partition('#')
        resolved=doc.parent/path if path else doc
        links += 1
        if not resolved.exists(): errors.append(f'{doc}: missing {target}')
        elif fragment.startswith('L') and fragment[1:].isdigit() and not 1 <= int(fragment[1:]) <= len(resolved.read_text().splitlines()): errors.append(f'{doc}: bad line {target}')
index=json.loads((root/'contracts/cases/index.json').read_text())['cases']
for case_id in index:
    fixture=json.loads((root/'contracts/cases'/f'{case_id}.json').read_text())
    if fixture.get('id') != case_id: errors.append(f'{case_id}: fixture id mismatch')
print(f'docs={len(files)} links={links} cases={len(index)} errors={len(errors)}')
for error in errors: print(error)
raise SystemExit(bool(errors))
PY
git diff --check && git diff --exit-code -- csa csa-java && git status --short
```

Resultado: `gofmt -d` sem diferenças; 7 documentos, 100 links e 17 fixtures válidos, sem
erros; whitespace limpo e nenhuma alteração versionada em Python ou Java. O status
confirmou também o submódulo Kubernetes previamente modificado; ele não foi tocado.

## Subentregas 5B e 5C — adaptadores de CPU e tag

Data: 2026-09-24. Fontes Python lidas sem alteração. Diretório inicial: raiz `autoscalers/`.

### E5B/C-01 — inspeção das operações de referência

```bash
sed -n '1,320p' csa/scripts/adapt_cpu.py
sed -n '1,320p' csa/scripts/adapt_tag.py
rg -n -A45 -B8 'def get_running_pods|def get_current_mcpu|def get_spec_mcpu|def rollout_in_progress|def get_container_spec' csa/scripts/adapt_base.py
sed -n '1,260p' csa/scripts/initial_data.py
rg -n -A120 -B20 'func currentPodMCPU|func deploymentSpecMCPU|func deploymentLabelSelector' csa-go/internal/csa/evaluate_runtime.go
```

Resultado: o CPU do Python lista Pods uma vez para a sequência de resize e novamente ao
selecionar o máximo de CPU; preserva arredondamento ties-to-even, aplica primeiro o teto e
depois o piso, e interrompe no primeiro erro de resize sem rollback. Tag verifica rollout
antes de localizar `znn`, armazena a tag antes de avaliar movimento, segue a escada de cinco
tags e, em `update_cpu`, aplica o maior CPU dos Pods a todos os containers antes do patch.
Uma lista vazia de Pods em `get_current_mcpu` retorna `None`; uma lista não vazia sem valores
de CPU tenta `max([])` e falha. Nenhum teste foi executado contra Python ou cluster.

### E5B/C-02 — parsing de imagens e arredondamento Python

```bash
python3 - <<'PY'
import re
pattern=(r'^(?P<repository>[\w.\-_]+((?::\d+|)(?=/[a-z0-9._-]+/[a-z0-9._-]+))|)'
         r'(?:/|)(?P<image>[a-z0-9.\-_]+(?:/[a-z0-9.\-_]+|))'
         r'(?::(?P<tag>[\w.\-_]{1,127})|)$')
for image in ['znn:200k','team/znn:200k','registry/ team/znn:200k','registry.example/team/znn:200k','localhost:5000/team/znn:200k','/znn:200k','znn','ZNn:200k','team/znn']:
    match=re.match(pattern,image)
    print(f'{image!r} -> {match.groupdict() if match else None}')
for current,multiplier in [(125,0.5),(127,0.5),(600,0.5),(700,0.9),(700,2.0)]:
    print(f'round({current}*{multiplier}) -> {round(current*multiplier)}')
PY
rg -n 'image:' csa/custom-selfadapter*.yaml
```

Resultado: regex probe confirmou imagens simples, imagens namespaced, registry com/sem porta,
barra inicial opcional, ausência de tag, imagem maiúscula inválida e limite da gramática. Os
valores de arredondamento `62` e `64` para empates foram usados nos testes Go. Os manifests
consultados usam registry e tags de perfil; não foram alterados.

### E5B/C-03 — testes Go RED antes da implementação

Diretório: `autoscalers/csa-go/`; cache: `/tmp/csa-go-gocache`.

```bash
gofmt -w internal/csa/adapt_tag_test.go && GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestAdaptCPU|TestAdaptTag|TestImageReference|TestReplaceImageTag|TestKubernetesClientUsesPodResize' -count=1
```

Resultado RED: código 1; compilação dos novos testes indicou ausência dos fluxos/helpers de
CPU e tag (`adjustedCPUMilli`, parser/substituição de imagem, navegação da tag e `resizePod`).

### E5B/C-04 — testes focados GREEN

```bash
gofmt -w internal/csa/adapt_cpu.go internal/csa/adapt_tag.go internal/csa/adapt_cpu_test.go internal/csa/adapt_tag_test.go internal/csa/initial_data_test.go && GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run 'TestAdaptCPU|TestAdaptTag|TestImageReference|TestReplaceImageTag|TestKubernetesClientUsesPodResize|TestAdaptReplicas' -count=1
```

Resultado GREEN: código 0; os casos focados de CPU, tag, réplicas e subrecurso Kubernetes
passaram. Testes adicionais reproduziram persistência do baseline/tag, duas respostas
sequenciais da lista de Pods, logs de limite CPU, `update_cpu` com Pods ausentes e limites
CPU para multiplicadores finitos extremos.

### E5B/C-04a — arredondamento grande com clamp depois do cálculo

```bash
gofmt -w internal/csa/adapt_cpu_test.go && GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestAdaptCPUUsesPythonRoundingAndBounds$' -count=1
```

Resultado RED: os dois casos de multiplicadores `1e300` e `-1e300` falharam porque o cálculo
Go então rejeitava o inteiro intermediário fora de `int64`. O Python arredonda o valor finito
para inteiro e depois aplica os limites `maxCPU` e CPU inicial.

```bash
gofmt -w internal/csa/adapt_cpu.go internal/csa/adapt_cpu_test.go && GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestAdaptCPUUsesPythonRoundingAndBounds$' -count=1
```

Resultado GREEN: os limites extremos e os casos ties-to-even passaram. O Go mantém o inteiro
arredondado em `big.Int` até aplicar os mesmos limites sequenciais do Python.

### E5B/C-05 — retorno temporário a RED e restauração

Os entry points dos modos foram temporariamente alterados para retornar `1`. Em verificações
direcionadas, a persistência da tag, a rejeição de imagem sem tag e o clamp do CPU intermediário
também foram temporariamente retirados. Os arquivos originais foram guardados em `/tmp` e
restaurados por `trap` mesmo com o teste falhando. O teste foi considerado correto somente
porque retornou código diferente de zero.

```bash
backup=/tmp/csa-go-adapt-cpu.backup
cp internal/csa/adapt_cpu.go "$backup"
restore() { cp "$backup" internal/csa/adapt_cpu.go; }
trap restore EXIT
python3 - <<'PY'
from pathlib import Path
path = Path("internal/csa/adapt_cpu.go")
source = path.read_text()
needle = ") int {\n\tlogger, err := newAdapterLogger(\"adapt_cpu\""
replacement = ") int {\n\treturn 1 // temporary RED check\n\tlogger, err := newAdapterLogger(\"adapt_cpu\""
if needle not in source:
    raise SystemExit("could not locate runAdaptCPU entry")
path.write_text(source.replace(needle, replacement, 1))
PY
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestAdaptCPU' -count=1
result=$?
echo "RED_EXIT_CODE=$result"
test "$result" -ne 0
```

```bash
backup=/tmp/csa-go-initial-data.backup
cp internal/csa/initial_data.go "$backup"
restore() { cp "$backup" internal/csa/initial_data.go; }
trap restore EXIT
python3 - <<'PY'
from pathlib import Path
import re
path = Path("internal/csa/initial_data.go")
source = path.read_text()
pattern = r"func \(store \*initialDataStore\) storeTag\(tag string\) error \{.*?\n\}"
replacement = "func (store *initialDataStore) storeTag(tag string) error {\n\treturn nil // temporary RED check\n}"
updated, count = re.subn(pattern, replacement, source, count=1, flags=re.S)
if count != 1:
    raise SystemExit("could not locate storeTag")
path.write_text(updated)
PY
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestAdaptTagStoresInitialTagBeforeChangingImage$' -count=1
result=$?
echo "RED_EXIT_CODE=$result"
test "$result" -ne 0
```

```bash
gofmt -w internal/csa/adapt_tag_test.go
backup=/tmp/csa-go-adapt-tag-no-tag.backup
cp internal/csa/adapt_tag.go "$backup"
restore() { cp "$backup" internal/csa/adapt_tag.go; }
trap restore EXIT
python3 - <<'PY'
from pathlib import Path
path = Path("internal/csa/adapt_tag.go")
source = path.read_text()
needle = "if !ok || !parts.hasTag {"
if needle not in source:
    raise SystemExit("could not locate image tag validation")
path.write_text(source.replace(needle, "if !ok {", 1))
PY
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestAdaptTagImageWithoutTagReturnsError$' -count=1
result=$?
echo "RED_EXIT_CODE=$result"
test "$result" -ne 0
```

```bash
backup=/tmp/csa-go-adapt-tag.backup
cp internal/csa/adapt_tag.go "$backup"
restore() { cp "$backup" internal/csa/adapt_tag.go; }
trap restore EXIT
python3 - <<'PY'
from pathlib import Path
path = Path("internal/csa/adapt_tag.go")
source = path.read_text()
needle = ") int {\n\tlogger, err := newAdapterLogger(\"adapt_tag\""
replacement = ") int {\n\treturn 1 // temporary RED check\n\tlogger, err := newAdapterLogger(\"adapt_tag\""
if needle not in source:
    raise SystemExit("could not locate runAdaptTag entry")
path.write_text(source.replace(needle, replacement, 1))
PY
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestAdaptTag' -count=1
result=$?
echo "RED_EXIT_CODE=$result"
test "$result" -ne 0
```

```bash
backup=/tmp/csa-go-adapt-cpu-bigint.backup
cp internal/csa/adapt_cpu.go "$backup"
restore() { cp "$backup" internal/csa/adapt_cpu.go; }
trap restore EXIT
python3 - <<'PY'
from pathlib import Path
path = Path("internal/csa/adapt_cpu.go")
source = path.read_text()
needle = "\tscaled := float64(current) * multiplier\n\tif math.IsNaN(scaled) || math.IsInf(scaled, 0) {"
replacement = "\tscaled := float64(current) * multiplier\n\tif scaled > math.MaxInt64 || scaled < math.MinInt64 {\n\t\treturn nil, fmt.Errorf(\"scaled CPU is outside int64 range\")\n\t}\n\tif math.IsNaN(scaled) || math.IsInf(scaled, 0) {"
if needle not in source:
    raise SystemExit("could not locate scaled CPU guard")
path.write_text(source.replace(needle, replacement, 1))
PY
GOCACHE=/tmp/csa-go-gocache go test ./internal/csa -run '^TestAdaptCPUUsesPythonRoundingAndBounds$' -count=1
result=$?
echo "RED_EXIT_CODE=$result"
test "$result" -ne 0
```

Resultado RED: os testes dos dois modos falharam pelos códigos de saída e stdout vazios quando
os fluxos foram retirados; os testes direcionados também falharam quando a persistência da tag,
a rejeição de imagem sem tag e o clamp dos multiplicadores grandes foram desativados. Os
backups foram restaurados pelo `trap`; os testes focados passaram em seguida.

### E5B/C-06 — suíte completa, build, formato e preservação

No diretório `autoscalers/csa-go/`:

```bash
GOCACHE=/tmp/csa-go-gocache go test ./... -count=1
GOCACHE=/tmp/csa-go-gocache go vet ./...
GOCACHE=/tmp/csa-go-gocache go build -o /tmp/csa-go ./cmd/csa-go
gofmt -d internal/csa/*.go
```

No diretório `autoscalers/`:

```bash
git diff --check
git diff --exit-code -- csa csa-java
```

Resultado: suíte Go, `go vet`, build, `gofmt` e whitespace passaram. Os diretórios Python e
Java não apresentam alterações versionadas. Nenhum teste de carga, Locust, script
`run_tests*` ou comando Kubernetes foi executado. A validação documental registrou
`docs=7 links=101 cases=19 errors=0`.
