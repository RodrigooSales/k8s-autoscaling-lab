# CSA Java HTTP — histórico de desenvolvimento

Registro da criação inicial da implementação independente CSA Java HTTP. O documento distingue decisões acordadas, estrutura já criada, comandos executados e validações ainda pendentes. Deve ser atualizado à medida que o trabalho avançar.

## Objetivo e escopo atual

Criar uma implementação CSA Java independente, orientada a serviço HTTP e com ciclo de adaptação próprio. A implementação poderá usar as outras versões como referência para paridade comportamental, sem depender do runtime genérico que executa etapas por shell.

O projeto Spring Boot expõe o health endpoint. A política de avaliação é verificada pelas 10 fixtures compartilhadas. A coleta de métrica externa e a leitura das réplicas do Deployment foram implementadas como um serviço independente, mas ainda não estão conectadas ao ciclo periódico. As adaptações no Kubernetes, a integração operacional com o recurso `CustomSelfAdapter` e os manifests ainda não foram implementados.

## Decisões

### Arquitetura

- O serviço Java será responsável pelo ciclo periódico de coleta, avaliação e adaptação.
- O operator existente continuará separado. Ele observa o recurso `CustomSelfAdapter` e provisiona o Pod; não fará parte do ciclo periódico do CSA.
- O Pod da implementação terá um container com o serviço Java HTTP. Não haverá um segundo container para executar o runtime shell genérico.
- O serviço terá endpoints HTTP de operação, começando por saúde. A implementação não será um conjunto de callbacks HTTP chamado pelo runtime shell existente.
- A paridade será definida pelos efeitos observáveis com entradas e estado equivalentes: decisão selecionada, réplicas, CPU, tag da imagem, baseline e comportamento durante rollouts.
- Não haverá compartilhamento de lógica de produção com as implementações anteriores. Casos de contrato poderão ser usados para verificar a paridade quando a lógica for desenvolvida.
- A primeira unidade de lógica é `EvaluationService`: recebe métricas e estado já normalizados e retorna a decisão, sem consultar o Kubernetes. Mantém os limiares de `0.95` e `0.90`, a prioridade réplicas → CPU → tag e os limites configurados. O intervalo entre os limiares não seleciona adaptação.
- O contrato compartilhado de avaliação será lido diretamente por um teste Java. Isso verifica todas as decisões já definidas sem copiar fixtures ou criar um framework de teste próprio.

### Coleta de métricas

- A coleta é própria desta implementação HTTP. As versões Python, Java shell e Go shell recebem um payload que o runtime genérico já coletou; copiar seus hooks de `metric` não faria uma consulta ao Kubernetes.
- `MetricCollectionService` consulta a API `external.metrics.k8s.io/v1beta1` por `CustomObjectsApi` e lê o Deployment por `AppsV1Api`. Ela usa o `spec.replicas` do Deployment, o alvo configurado e todas as entradas de `items` devolvidas para a métrica externa.
- O runtime genérico usa `Quantity.MilliValue()` para cada série externa e soma os valores. A implementação HTTP mantém essa unidade: cada `value` é lido como Kubernetes `Quantity`, multiplicado por 1000 com arredondamento para cima ao milésimo, e somado em `BigInteger`. O alvo permanece na unidade configurada porque `EvaluationService` o escala por 1000.
- Uma resposta sem entradas não vira zero nem uma decisão sem ação. O coletor lança `MetricCollectionException`, alinhado ao comportamento operacional observado quando o adapter externo não publica valor. A consulta da métrica ocorre antes da leitura do Deployment; quando não há amostras, o ciclo evita a chamada adicional ao Deployment.
- Namespace, Deployment, nome/seletor da métrica e alvo vêm de `application.yaml` e são injetados diretamente em `MetricCollectionService`. A aplicação fornece sob demanda um único cliente via `Config.defaultClient()`; os wrappers de API dos serviços compartilham esse `ApiClient` e suas conexões. Não há `Configuration.setDefaultApiClient` global.
- O coletor não lista Pods para calcular `ReadyPodCount`: a avaliação CSA atual usa réplicas desejadas do Deployment e não consome essa contagem. Em ciclos com métrica, são necessárias uma consulta à métrica e uma leitura do Deployment; não se afirma ganho de latência sem medições.
- O bean do cliente e os serviços Kubernetes são lazy para que o health endpoint local possa iniciar sem kubeconfig ou cluster. Quando uma operação for chamada, o cliente usa a configuração in-cluster ou kubeconfig padrão do SDK.

### Tecnologia

- Java 21, alinhado às demais implementações Java do repositório.
- Spring Boot 4.1.1 e Spring MVC para o servidor HTTP embutido. Essa escolha reduz infraestrutura HTTP escrita à mão e segue o stack Java convencional para serviços.
- Spring Boot Actuator para os health checks usados pelo Kubernetes.
- Agendamento periódico com atraso fixo entre conclusões de ciclo, evitando iniciar uma nova avaliação antes de a anterior terminar.
- Kubernetes Java Client 26.0.0, igual à versão usada em `autoscalers/csa-java` para manter o mesmo cliente Kubernetes nesta comparação.
- Gradle Wrapper 9.3.0, copiado do projeto CSA Java existente.
- Não iniciar com WebFlux, virtual threads ou imagem nativa. A carga de requisições prevista é pequena; manter a JVM evita introduzir outra variável na comparação inicial. Startup e memória do Spring Boot devem ser considerados nas medições.

> A escolha do Spring Boot se baseou na documentação oficial consultada em 2026-10-13: requisitos do [Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html), suporte a [Spring MVC](https://docs.spring.io/spring-boot/reference/web/index.html), [agendamento](https://docs.spring.io/spring-framework/reference/integration/scheduling.html) e [health endpoint do Actuator](https://docs.spring.io/spring-boot/api/rest/actuator/health.html). O servidor HTTP mínimo do próprio JDK foi considerado, mas não escolhido para evitar implementar manualmente health checks e infraestrutura de serviço.

### Branch e ambiente de trabalho

- Branch local: `feat/csa-java-http`, criada a partir de `dev` no commit `29d36b2`.
- Worktree: `.worktrees/csa-java-http`, conforme a convenção do repositório.
- O `.venv` da worktree é um link para o ambiente existente na raiz do checkout principal.
- O ambiente Vagrant existente é acessível pela worktree por meio de `.vagrant-env`, um link para o submódulo existente. O caminho `vagrant-kubeadm-kubernetes` dentro da worktree permanece como o placeholder vazio do submódulo.
- `.venv` é ignorado pelo `.gitignore`; `.vagrant-env` aparece como não rastreado e não deve ser incluído em commits.
- Não copiar, reprovisionar ou remover o `.venv` nem o estado Vagrant existente.

## Arquivos criados

Projeto em `autoscalers/csa-java-http/`:

- `build.gradle` — Spring Boot, dependências, toolchain Java 21 e execução de testes via JUnit Platform.
- `settings.gradle` — nome do projeto Gradle.
- `gradlew`, `gradlew.bat` e `gradle/wrapper/` — Gradle Wrapper 9.3.0, copiado do CSA Java.
- `.gitignore` — exclui `.gradle/`, `build/` e `.idea/`.
- `src/main/java/org/csajavahttp/CsaJavaHttpApplication.java` — entrada da aplicação Spring Boot.
- `src/main/java/org/csajavahttp/evaluate/EvaluationService.java` — decisão pura de estratégia, com entradas normalizadas.
- `src/main/java/org/csajavahttp/metric/MetricCollectionService.java` — leitura do Deployment e da métrica externa, soma das séries, normalização para milésimos e configuração da coleta. O `MetricSnapshot` é um record aninhado nesse arquivo.
- `src/main/java/org/csajavahttp/metric/MetricCollectionException.java` — erro específico para configuração e respostas inválidas da coleta.
- `src/main/resources/application.yaml` — propriedades da coleta e exposição somente do health endpoint, com probes habilitados.
- `README.md` — objetivo, stack escolhida, estado atual e comandos para uso local.
- `src/test/java/org/csajavahttp/evaluate/EvaluationServiceContractTest.java` — comparação das decisões com as fixtures de `autoscalers/contracts/cases/`.
- `src/test/java/org/csajavahttp/metric/MetricCollectionServiceTest.java` — teste do cliente Kubernetes real contra um servidor HTTP local, incluindo soma, conversão, seletor, Deployment e resposta vazia.

## Levantamento de arquitetura

A discussão anterior à criação da branch verificou como o runtime genérico executa CSA atualmente:

- `internal/selfadapter/selfadapter.go` lê o workload, coleta métricas, chama avaliação e executa a estratégia.
- `internal/metricget/metricget.go` consulta métricas Kubernetes configuradas e chama a etapa `metric` pelo executor selecionado.
- `internal/evaluatecalc/evaluatecalc.go` passa a saída da métrica para `evaluate`.
- `internal/execute/shell/shell.go` executa um processo e passa a entrada por stdin; `internal/execute/http/http.go` envia a entrada numa requisição e devolve o corpo da resposta.
- A configuração `autoscalers/csa/config.yaml` usa intervalo de 5 segundos e uma métrica `External` chamada `znn_latency_ms_p95`. O script `metric_nginx_req_duration.py` organiza os valores recebidos para `evaluate`.

Esse levantamento esclareceu que `shell` e `http` são mecanismos de execução das etapas do runtime atual. A nova implementação Java HTTP terá seu próprio ciclo, em vez de apenas trocar o transporte dessas etapas.

## Comandos executados

Os comandos abaixo foram executados durante a investigação e a criação inicial. Os comandos que mencionam diretórios relativos foram executados no diretório de trabalho indicado.

### Continuação: scaffold e avaliação

Executados na raiz da worktree, salvo comandos Gradle e Java, executados em `autoscalers/csa-java-http/`:

```sh
git status --short --branch && git branch --show-current && rg --files autoscalers/csa-java-http docs/csa-java-http autoscalers/contracts/cases autoscalers/csa-java/src autoscalers/csa-go
./gradlew bootJar
cat autoscalers/contracts/cases/index.json && cat autoscalers/contracts/cases/evaluate_rate_095_replica_priority.json && cat autoscalers/contracts/cases/evaluate_rate_096_cpu_fallback.json && cat autoscalers/contracts/cases/evaluate_rate_098_tag_fallback.json && cat autoscalers/contracts/cases/evaluate_rate_0949_deadband.json
sed -n '1,260p' autoscalers/csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java && sed -n '1,260p' autoscalers/csa-go/internal/csa/evaluate.go && sed -n '1,220p' autoscalers/csa-go/internal/csa/evaluate_runtime.go
python3 -c 'import json,pathlib; d=pathlib.Path("autoscalers/contracts/cases"); idx=json.loads((d/"index.json").read_text()); [(lambda x: print(x["id"], "input=", x.get("input"), "config=", x.get("config"), "state=", x.get("state"), "expected=", x.get("expectedResult")))(json.loads((d/(case+".json")).read_text())) for case in idx["cases"] if case.startswith("evaluate_")]'
sed -n '1,240p' docs/csa-java-http/historico-de-desenvolvimento.md
cat autoscalers/csa-java-http/build.gradle && cat autoscalers/csa-java-http/src/main/resources/application.yaml && cat autoscalers/csa-java-http/README.md
sed -n '1,240p' autoscalers/csa-java/build.gradle && sed -n '1,180p' autoscalers/csa-java-http/build.gradle
```

`./gradlew bootJar` inicialmente não conseguiu criar o lock no cache em `/home/rodrigo/.gradle`; repetido com a autorização fora do sandbox, compilou o scaffold com sucesso. A leitura das 10 fixtures `evaluate_*` confirmou que elas cobrem prioridade, limites de réplicas e CPU, reduções de carga, estratégias desabilitadas e a banda sem ação.

Foi criado primeiro um `EvaluationService` sem regra e depois o teste de contrato. A primeira execução do teste encontrou `No tests found` porque o projeto ainda não estava configurado para JUnit Platform. Foi acrescentado `tasks.named('test') { useJUnitPlatform() }` ao `build.gradle`; isso habilita o runner exigido pelo starter de teste já usado, sem adicionar dependência.

O ciclo RED–GREEN–RED foi executado com:

```sh
./gradlew test --tests org.csajavahttp.evaluate.EvaluationServiceContractTest
./gradlew test
```

Com o serviço-esqueleto, o contrato falhou em `evaluate_rate_095_replica_priority`: esperava `adapt_replicas` com `replicas: 2`, recebeu `null` (RED). A política mínima foi implementada e as 10 fixtures passaram (GREEN). A regra foi removida temporariamente e a mesma fixture voltou a falhar (RED); em seguida a regra foi restaurada. A suíte final passou.

O teste usa o contrato versionado em vez de duplicá-lo e não chama Kubernetes. A avaliação recebe réplicas atuais, valores de métrica inteiros, estado de CPU, limites e estratégias habilitadas. Ela escala o target por 1000, calcula a taxa, escolhe a primeira estratégia aplicável na ordem já usada pelas implementações existentes e retorna a mesma forma `{strategy, parameters}`. Um target zero é rejeitado explicitamente.
Na revisão final, o helper que apenas construía um `Decision` foi removido; as decisões são criadas diretamente no ponto de escolha.

Build e health check do artefato final:

```sh
./gradlew build
java -jar build/libs/csa-java-http-0.1.0-SNAPSHOT.jar --server.port=18080
curl --fail --silent --show-error http://127.0.0.1:18080/actuator/health
```

`./gradlew build` terminou em `BUILD SUCCESSFUL`. A primeira tentativa de iniciar o serviço em sandbox falhou ao abrir o socket com `Operation not permitted`; executado fora do sandbox na porta 18080, o serviço iniciou e o health endpoint respondeu `{"groups":["liveness","readiness"],"status":"UP"}`. O processo foi encerrado após a consulta.
Depois da simplificação da classe, `./gradlew build` foi repetido e passou com testes, `bootJar` e `jar`; o health check foi repetido no artefato recém-gerado e respondeu `UP`.

### Inspeção do checkout principal

Executados no checkout principal do repositório:

```sh
git status --short --branch
git branch --show-current
git worktree list --porcelain
ls -ld .venv vagrant-kubeadm-kubernetes .worktrees
sed -n '1,220p' autoscalers/csa-java/build.gradle
sed -n '1,180p' autoscalers/csa-java/Dockerfile
sed -n '1,220p' .gitignore
cat autoscalers/csa-java/gradle/wrapper/gradle-wrapper.properties
git submodule status
find .worktrees -mindepth 1 -maxdepth 1 -print
sed -n '1,220p' .codex/AGENTS.md
```

O checkout principal estava na branch `dev`, em `29d36b2`. O submódulo Vagrant aparecia modificado antes da criação da worktree; essa alteração foi preservada.
O último comando procurou instruções nesse caminho, mas `.codex/AGENTS.md` não existia no checkout; as instruções de desenvolvimento aplicadas foram fornecidas na conversa.

### Inspeção do runtime e da configuração CSA

Uma primeira busca foi executada com o diretório de trabalho incorreto e falhou porque os caminhos não existiam ali:

```sh
rg -n "func .*Adapt|CollectMetric|collectMetric|Metric|Evaluate|Execute" internal/selfadapter internal/execute config
```

A localização correta do repositório irmão foi encontrada com:

```sh
rg --files .. -g 'selfadapter.go' -g 'http.go' -g 'shell.go' -g 'execute.go'
```

No repositório irmão `custom-self-adapter/custom-self-adapter`, foram consultados:

```sh
sed -n '1,260p' internal/selfadapter/selfadapter.go && sed -n '1,220p' internal/execute/execute.go && sed -n '1,220p' internal/execute/shell/shell.go && sed -n '1,220p' internal/execute/http/http.go
rg -n "type .*Metric|getMetric|ExecuteWithValue|Metrics" internal/metricget metric config autoscalers -g '*.go' -g '*.yaml'
sed -n '40,160p' internal/metricget/metricget.go && sed -n '1,180p' internal/evaluatecalc/evaluatecalc.go
```

A busca `rg` acima incluiu `autoscalers`, que não existe no repositório irmão; ela ainda encontrou as referências presentes nas demais pastas. A configuração Python foi então consultada no checkout principal:

```sh
sed -n '1,220p' autoscalers/csa/config.yaml && sed -n '1,200p' autoscalers/csa/scripts/metric_nginx_req_duration.py && rg -n "KubernetesMetricSpecs|PreMetric|metric:" autoscalers
```

### Criação da branch e preparação da worktree

Executados no checkout principal:

```sh
git worktree add -b feat/csa-java-http .worktrees/csa-java-http dev
```

Executados na raiz da worktree `.worktrees/csa-java-http`:

```sh
ln -s /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/.venv .venv
find vagrant-kubeadm-kubernetes -mindepth 1 -maxdepth 1 -print
rmdir vagrant-kubeadm-kubernetes
ln -s /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes vagrant-kubeadm-kubernetes
```

O último link ocupou o caminho registrado como submódulo e fez o `git status` falhar com `expected submodule path 'vagrant-kubeadm-kubernetes' not to be a symbolic link`. O link foi movido para um nome auxiliar e o placeholder vazio foi recriado:

```sh
mv vagrant-kubeadm-kubernetes .vagrant-env
mkdir vagrant-kubeadm-kubernetes
```

O link `.vagrant-env` aponta para o submódulo original e permite reutilizar o mesmo ambiente. A tentativa não alterou nem copiou o estado Vagrant.

### Criação do esqueleto Gradle

Executados na raiz da worktree:

```sh
mkdir -p autoscalers/csa-java-http/gradle/wrapper autoscalers/csa-java-http/src/main/java/org/csajavahttp autoscalers/csa-java-http/src/main/resources
cp autoscalers/csa-java/gradlew autoscalers/csa-java-http/gradlew
cp autoscalers/csa-java/gradlew.bat autoscalers/csa-java-http/gradlew.bat
cp autoscalers/csa-java/gradle/wrapper/gradle-wrapper.jar autoscalers/csa-java-http/gradle/wrapper/gradle-wrapper.jar
cp autoscalers/csa-java/gradle/wrapper/gradle-wrapper.properties autoscalers/csa-java-http/gradle/wrapper/gradle-wrapper.properties
```

Os arquivos listados em [Arquivos criados](#arquivos-criados) foram adicionados por patch. Nenhum arquivo foi adicionado ao índice Git; o projeto e o link `.vagrant-env` continuam locais e não commitados.

### Inspeções finais executadas

Na raiz da worktree:

```sh
git status --short --branch
readlink -f .venv
readlink -f .vagrant-env
git diff --check
find autoscalers/csa-java-http -maxdepth 8 -type f -print
```

O `git diff --check` inicial foi executado com os arquivos ainda não rastreados, portanto não verificou o conteúdo deles. Nesta continuação foram executados `./gradlew test --tests org.csajavahttp.evaluate.EvaluationServiceContractTest`, `./gradlew test`, `./gradlew build` e o health check HTTP descrito acima. Não foram executados comandos Vagrant nem testes no cluster.

Na revisão final da worktree:

```sh
git status --short --branch && git branch --show-current && readlink -f .venv && readlink -f .vagrant-env
rg -n '[[:blank:]]+$' autoscalers/csa-java-http docs/csa-java-http
rg -n "não foram executados|somente a estrutura|a avaliação.*ainda não|esqueleto" docs/csa-java-http autoscalers/csa-java-http
```

O branch continuou em `feat/csa-java-http`; `.venv` e `.vagrant-env` resolveram para os ambientes do checkout principal. A busca por whitespace no fim de linha não encontrou ocorrências. A busca de texto desatualizado apontou apenas a referência histórica ao scaffold e ao nome de seção “Criação do esqueleto Gradle”.

## Estado ao final deste registro

- `feat/csa-java-http` existe localmente e parte de `dev`.
- A worktree `.worktrees/csa-java-http` continua ativa para o desenvolvimento desta implementação.
- `autoscalers/csa-java-http/` contém a aplicação Spring Boot, o health endpoint do Actuator e a primeira política de avaliação coberta pelas fixtures compartilhadas.
- `./gradlew build` concluiu com sucesso; `/actuator/health` respondeu `UP` no artefato final.
- `docs/csa-java-http/` registra as decisões e operações feitas até aqui.
- Não foi criado commit, push ou pull request.
- O checkout principal continua na `dev`; a alteração preexistente no submódulo Vagrant foi preservada.

## Continuação: coleta da métrica externa

### Investigação

Antes de implementar, foram comparadas as três versões CSA e o caminho de coleta do runtime genérico:

- Os hooks `metric` de Python, Java e Go apenas extraem campos de um payload que o runtime já preparou. Reutilizar esse código não consultaria Kubernetes.
- O runtime genérico consulta `external.metrics.k8s.io`, converte cada `Quantity` retornada para milésimos e soma todas as séries. Ele também conta Pods Ready; essa contagem não é usada pela avaliação CSA atual.
- O contrato compartilhado `metric_first_metric` descreve a normalização do payload anterior, não a semântica da API de métricas. Portanto, ele não deve levar a nova versão a descartar todas as séries menos a primeira.
- Os registros funcionais Go descrevem respostas `items: []` quando o adapter externo ainda não publica a métrica. O coletor não trata essa condição como valor zero.
- O Kubernetes Java Client 26.0.0 tem `CustomObjectsApi` genérico para a rota agregada de métricas externas; o Deployment continua sendo lido pelo `AppsV1Api` tipado.

### Decisões desta etapa

- `MetricCollectionService` produz `MetricSnapshot` com réplicas desejadas atuais, métrica atual e alvo. Ainda não chama `EvaluationService` nem agenda ciclos.
- A configuração inicial em `application.yaml` aponta para `default/kube-znn`, a métrica `znn_latency_ms_p95`, sem seletor, com alvo `1000`. Os valores são injetados diretamente em `MetricCollectionService`.
- A consulta da métrica precede a leitura do Deployment. Uma lista vazia encerra a coleta com `MetricCollectionException` e evita a segunda chamada. Quando há amostras, a coleta faz uma consulta de métrica e uma leitura do Deployment; não solicita a lista de Pods.
- Todas as entradas `items[].value` são interpretadas como Kubernetes `Quantity`, convertidas a milésimos com arredondamento para cima e somadas em `BigInteger`. O valor do alvo segue na unidade configurada porque a avaliação existente aplica o fator 1000 ao alvo.
- `Config.defaultClient()` e os wrappers de API são singletons lazy e compartilham o mesmo `ApiClient`; o health endpoint não inicializa Kubernetes. O código do CSA Python/Java/Go não é dependência de produção.
- A escolha evita trabalho com Pods que essa avaliação não usa e evita criar um cliente por ciclo. Ainda não há medição que permita afirmar menor latência total; a leitura do Deployment é necessária nesta arquitetura independente.

### TDD e validação

O teste `MetricCollectionServiceTest` chama os métodos reais `AppsV1Api` e `CustomObjectsApi` contra um servidor HTTP local. Ele verifica duas séries somadas, arredondamento de `1200.0001` para milésimos, seletor, leitura das 3 réplicas e tratamento de `items: []` sem buscar o Deployment.

O ciclo observado foi:

1. O teste foi escrito antes do coletor. A primeira execução falhou na compilação porque os tipos de coleta ainda não existiam.
2. Com o coletor criado, o primeiro retorno simulado foi rejeitado pelo SDK porque faltava `spec.template` no Deployment de teste. Foi completado o fixture com um Deployment válido; os dois testes então passaram.
3. O valor coletado foi substituído temporariamente por zero. Os testes falharam na soma e na resposta vazia; o valor correto foi restaurado e os testes passaram.
4. Foram adicionadas expectativas para consultar a métrica antes do Deployment e não ler o Deployment quando `items` está vazio. Os testes falharam com a ordem anterior; após a alteração, passaram. A ordem antiga foi restaurada temporariamente e os dois testes falharam novamente; a ordem otimizada foi restaurada.
5. `./gradlew build` terminou em `BUILD SUCCESSFUL`, incluindo a suíte completa e os JARs.
6. O JAR iniciou fora do sandbox na porta 18080 sem inicializar cliente Kubernetes. `/actuator/health` respondeu `{"groups":["liveness","readiness"],"status":"UP"}`. Nenhum teste de cluster, comando Vagrant ou carga foi executado.

O servidor HTTP simulado também precisou de uma resposta Deployment completa porque o desserializador tipado do SDK valida `spec.template`, ainda que a coleta use somente `spec.replicas`. Isso é característica do teste do cliente, não uma leitura extra em produção.

### Comandos relevantes desta etapa

Executados na raiz da worktree `.worktrees/csa-java-http`, exceto os comandos Gradle, executados em `autoscalers/csa-java-http/`:

```sh
rg -n -C 4 'external|MilliValue|first|primeir|valor|metric' docs/csa-go/paridade.md
rg -n -C 3 'client|reutil|milli|metric|métrica|performance' docs/melhorias-de-performance.md
rg -n -C 4 'empty|vazio|ExternalMetric|MetricValue|Gather|metric' docs/csa-go/validacao-funcional.md docs/csa-go/integracao-e-timeouts.md docs/csa-go/comandos-investigacao.md
sed -n '1,260p' autoscalers/csa-go/internal/csa/metric.go
sed -n '1,260p' autoscalers/csa-go/internal/csa/metric_test.go
sed -n '1,220p' autoscalers/csa-java/src/main/java/org/csajava/runtime/metric/MetricRuntime.java
sed -n '1,220p' autoscalers/csa/config.yaml
find /home/rodrigo/.gradle/caches/modules-2/files-2.1/io.kubernetes -type f -name '*.jar'
javap -classpath /home/rodrigo/.gradle/caches/modules-2/files-2.1/io.kubernetes/client-java-api/26.0.0/8770f1582fe4845125df453241611af9f000d17c/client-java-api-26.0.0.jar 'io.kubernetes.client.openapi.apis.CustomObjectsApi$APIlistNamespacedCustomObjectRequest'
javap -classpath /home/rodrigo/.gradle/caches/modules-2/files-2.1/io.kubernetes/client-java-api/26.0.0/8770f1582fe4845125df453241611af9f000d17c/client-java-api-26.0.0.jar io.kubernetes.client.custom.Quantity
javap -classpath /home/rodrigo/.gradle/caches/modules-2/files-2.1/io.kubernetes/client-java-api/26.0.0/8770f1582fe4845125df453241611af9f000d17c/client-java-api-26.0.0.jar -c -p io.kubernetes.client.custom.QuantityFormatter
./gradlew test --tests org.csajavahttp.metric.MetricCollectionServiceTest
./gradlew build
java -jar build/libs/csa-java-http-0.1.0-SNAPSHOT.jar --server.port=18080
curl --fail --silent --show-error http://127.0.0.1:18080/actuator/health
git diff --check
```

O comando de teste focado foi repetido durante os estados RED/GREEN e a checagem de regressão; o resultado final da suíte está registrado acima. A primeira inicialização local e a primeira tentativa de `curl` foram bloqueadas pelo sandbox; repetidas fora dele, o health check passou e o processo foi encerrado. O acesso de rede foi apenas ao servidor local de teste/health check, sem chamadas ao cluster.

### Estado após a coleta (antes da adaptação de réplicas)

- Branch `feat/csa-java-http`; worktree `.worktrees/csa-java-http` continua ativa.
- O app tem health checks, avaliação independente coberta por contratos e coleta independente coberta por cliente Kubernetes contra HTTP local.
- A coleta não está conectada a agendamento nem a adaptação; não há manifests da implementação.
- `.venv` e Vagrant continuam reutilizados por links já existentes, sem alterações ou execução nesta etapa.
- Não foram feitos commit, push ou pull request. O checkout principal e a alteração preexistente do submódulo Vagrant foram preservados.

## Continuação: consolidação do coletor

### Decisão

Após revisar a quantidade de arquivos da coleta, a implementação foi consolidada em `MetricCollectionService.java`: a classe recebe as propriedades Spring diretamente, cria sob demanda o `ApiClient`, instancia os dois wrappers do SDK e contém o `MetricSnapshot` como record aninhado. `MetricCollectionException` continua em arquivo próprio para manter identificável o erro específico do módulo.

Essa organização removeu os arquivos separados `CsaMetricProperties.java`, `KubernetesClientConfiguration.java` e `MetricSnapshot.java` sem alterar as responsabilidades observáveis da coleta. O record aninhado preserva nomes e tipos para os três valores retornados; substituir isso por mapa/lista posicional ou estado mutável no serviço reduziria clareza e segurança de tipos. Não foi criada outra classe auxiliar.

### Validação

O teste existente da coleta continua cobrindo as chamadas reais do SDK contra HTTP local, a soma das séries, arredondamento, seletor, leitura das réplicas e resposta vazia. `./gradlew test --tests org.csajavahttp.metric.MetricCollectionServiceTest` e a suíte completa com `./gradlew test` passaram após a consolidação. O build completo não foi repetido nesta etapa; o `./gradlew build` anterior à consolidação havia passado.

### Comandos relevantes desta etapa

Executados na raiz da worktree `.worktrees/csa-java-http`, exceto o comando Gradle, executado em `autoscalers/csa-java-http/`:

```sh
rg -n "CsaMetricProperties|KubernetesClientConfiguration|MetricSnapshot|MetricCollectionService" autoscalers/csa-java-http docs/csa-java-http
./gradlew test --tests org.csajavahttp.metric.MetricCollectionServiceTest
./gradlew build
git diff --check
```

## Continuação: adaptação de réplicas

### Decisão e implementação

A primeira operação de escrita é `ReplicaAdaptationService.adaptReplicas`. Ela envia um strategic merge patch contendo somente `spec.replicas` para o Deployment configurado. Assim, não precisa ler e reenviar o Deployment inteiro para alterar esse campo. Erros da API Kubernetes são propagados em `AdaptationException`, separado em seu próprio arquivo.

Para que coleta e adaptação usem as mesmas conexões do cliente Kubernetes, `CsaJavaHttpApplication` fornece um único bean `ApiClient`, criado sob demanda. `MetricCollectionService` passou a recebê-lo por injeção, e `ReplicaAdaptationService` usa o mesmo bean. Os serviços continuam lazy; iniciar o health endpoint não carrega kubeconfig nem tenta conectar ao cluster.

`AppsV1Api` vem da dependência `io.kubernetes:client-java-api:26.0.0`; o tipo está no pacote `io.kubernetes.client.openapi.apis` e sua implementação gerada fica no [repositório oficial do cliente Java](https://github.com/kubernetes-client/java/blob/master/kubernetes/src/main/java/io/kubernetes/client/openapi/apis/AppsV1Api.java). Os campos locais que guardam esse cliente se chamam `deploymentApi`, que descreve seu uso para consultar e alterar o Deployment. O tipo oficial do SDK permanece igual ao da dependência.

Esta etapa não conecta a adaptação ao `EvaluationService` nem cria o ciclo periódico. Ela estabelece e testa a chamada de escrita Kubernetes de forma isolada. CPU, tag, baseline e execução periódica continuam pendentes.

### TDD e validação

`ReplicaAdaptationServiceTest` envia a chamada ao cliente Kubernetes real contra um servidor HTTP local. O teste confirma que há somente uma requisição `PATCH`, com strategic merge patch e apenas `spec.replicas`; outro caso confirma que uma resposta HTTP 403 vira `AdaptationException` com a identidade do Deployment.

O teste foi escrito antes da implementação e falhou na compilação porque `ReplicaAdaptationService` e `AdaptationException` ainda não existiam. Depois da implementação, o teste focado e a suíte completa passaram. Não houve chamada ao cluster, execução Vagrant ou teste de carga.

### Comandos relevantes desta etapa

Executados em `autoscalers/csa-java-http/`:

```sh
./gradlew test --tests org.csajavahttp.adapt.ReplicaAdaptationServiceTest
./gradlew test
rg -n "\bapps\b|AppsV1Api" autoscalers/csa-java-http/src/main/java autoscalers/csa-java-http/src/test/java
```

## Validação funcional sequencial no cluster

Em 13/10/2026, depois de Python e Go, o serviço Java foi chamado contra a fixture temporária `csa-replica-smoke-20261013/replica-target`, descrita no [histórico CSA Python HTTP](../csa-python-http/historico-de-desenvolvimento.md#validação-funcional-sequencial-no-cluster). Um teste JUnit temporário construiu `ReplicaAdaptationService`, aplicou a quantidade solicitada e leu o Deployment de volta por `AppsV1Api`; o arquivo do runner foi removido após a validação.

O primeiro runner tentou `Config.defaultClient()` com o kubeconfig Vagrant e falhou porque essa configuração de cliente não tem a classe opcional `org.bouncycastle.util.io.pem.PemObjectGenerator` no classpath. Para não introduzir dependências fora do escopo, foi usado `kubectl proxy`, que autentica com o kubeconfig já existente; o runner Java apontou um `ApiClient` sem credenciais para o proxy local. Nenhum código de produção Java foi alterado por essa escolha.

Executados na pasta `autoscalers/csa-java-http`:

```sh
kubectl --kubeconfig /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config proxy --address=127.0.0.1 --port=8001 --accept-hosts='^127\.0\.0\.1$'
CSA_RUN_CLUSTER_TESTS=1 CSA_API_URL=http://127.0.0.1:8001 CSA_NAMESPACE=csa-replica-smoke-20261013 CSA_DEPLOYMENT_NAME=replica-target CSA_TEST_REPLICAS=1 ./gradlew test --tests org.csajavahttp.adapt.ReplicaAdaptationClusterSmokeTest
CSA_RUN_CLUSTER_TESTS=1 CSA_API_URL=http://127.0.0.1:8001 CSA_NAMESPACE=csa-replica-smoke-20261013 CSA_DEPLOYMENT_NAME=replica-target CSA_TEST_REPLICAS=0 ./gradlew test --rerun-tasks --tests org.csajavahttp.adapt.ReplicaAdaptationClusterSmokeTest
```

A primeira execução confirmou uma réplica e a segunda confirmou zero. A tentativa de reset sem `--rerun-tasks` ficou `UP-TO-DATE` porque mudanças em variáveis de ambiente não invalidam a tarefa Gradle; a repetição forçada executou e passou. O proxy foi encerrado e a namespace temporária foi removida. Não houve alteração em `kube-znn`, teste de carga, commit, push ou PR.

Após remover o runner temporário, `./gradlew test` passou para a suíte Java normal. A primeira tentativa foi bloqueada pelo lock somente leitura no cache do Gradle; a repetição autorizada concluiu com `BUILD SUCCESSFUL`.

## Continuação: empacotamento para testes futuros

### Configuração adicionada

- `autoscalers/csa-java-http/Dockerfile` constrói o `bootJar` no estágio Gradle e executa somente o serviço Java em uma imagem Java 21, na porta 8080, como usuário numérico não root. O argumento `PROFILE` escolhe o YAML copiado como `/app/config/application.yaml`; `spring.config.additional-location=file:/app/config/` faz o Spring Boot ler essa configuração externa junto da configuração incluída no JAR. O padrão é `h`.
- `autoscalers/csa-java-http/profiles/{h,hq,v,vq}.yaml` registram configurações CSA para o Deployment e a métrica usados no laboratório, limites de réplicas/CPU e estratégias de cada perfil. Os serviços atuais leem namespace, Deployment, métrica, seletor e alvo. `interval`, limites de réplicas/CPU e `enabled-strategies` ainda não estão conectados a um ciclo de execução e avaliação. `h` é o único profile alinhado à adaptação já implementada. `adapt_cpu` e `adapt_tag` aparecem nos outros conjuntos, mas os adaptadores Java HTTP ainda não existem.
- `.dockerignore` exclui o cache Gradle, o diretório de build e configurações do IntelliJ do contexto enviado ao Docker.
- `custom-selfadapter-template.yaml` e `custom-selfadapter-{h,hq,v,vq}.yaml` são instâncias da CRD compartilhada `CustomSelfAdapter`. O schema da CRD permanece em `charts/custom-self-adapter-operator/templates/crd/customselfadapters.custom-self-adapter.net.yaml`; não foi criada outra definição de CRD.
- Os manifests criam o Pod de um container com readiness/liveness do Actuator e recursos de CPU/memória. `roleRequiresMetricsServer` fica `false`; a regra adicional concede somente `get` e `list` da métrica `znn_latency_ms_p95` em `external.metrics.k8s.io`. O Role padrão do operador já concede acesso ao Deployment para leitura e patch de réplicas.
- A arquitetura Java HTTP continua independente do executor de callbacks HTTP do runtime genérico. O executor genérico envia a entrada inteira no corpo quando `parameterMode` é `body`, mas os profiles aqui não o configuram: o serviço deverá executar seu próprio ciclo periódico. Hoje a aplicação expõe apenas `/actuator/health` e seus grupos readiness/liveness; ela ainda não agenda o ciclo de coleta-avaliação-adaptação. Os recursos são, portanto, preparação de empacotamento, não uma configuração funcional de autoscaling.

### Comandos de inspeção executados

Na raiz do repositório, foram consultados os padrões Python/Go e o schema local da CRD:

```sh
rg --files autoscalers/csa-java-http docs/csa-java-http autoscalers/csa-go autoscalers/csa
rg --files | rg 'customselfadapter|custom-self-adapter|crd|Dockerfile|profiles/.+\.ya?ml|config\.ya?ml'
sed -n '1,320p' charts/custom-self-adapter-operator/templates/crd/customselfadapters.custom-self-adapter.net.yaml
```

Na worktree `.worktrees/csa-java-http`, foram lidos o `build.gradle`, `application.yaml`, `README.md`, os serviços Java existentes e o histórico. Também foram consultados os tipos e a reconciliação do operador irmão e o contrato do executor HTTP:

```sh
sed -n '1,300p' ../custom-self-adapter-operator/api/v1/customselfadapter_types.go
sed -n '150,420p' ../custom-self-adapter-operator/internal/controller/customselfadapter_controller.go
sed -n '1,280p' ../custom-self-adapter/internal/execute/http/http.go
cat ../custom-self-adapter/config/execute.go
sed -n '45,150p' ../custom-self-adapter/config/core.go
```

### Validação

Por instrução desta atividade, não foram executados build, suíte de testes, construção ou publicação de imagem, `kubectl apply` nem testes no cluster. A validação estática fez parse dos dez YAMLs com Ruby e confirmou que cada profile e manifesto compartilha o mesmo sufixo; `rg` não encontrou espaços finais. `git diff --check` terminou sem erros. O `python3` do sistema não tinha PyYAML, então a validação YAML usou a biblioteca padrão Ruby. Os campos usados seguem o schema compartilhado e os tipos `PodTemplateSpec`, `PolicyRule` e `CustomSelfAdapterConfig` do operador. Nenhum endpoint operacional além de health foi presumido como implementado.

Comandos de validação estática executados na worktree:

```sh
ruby -e 'require "yaml"; files = Dir.glob("autoscalers/csa-java-http/profiles/*.yaml") + Dir.glob("autoscalers/csa-java-http/custom-selfadapter-*.yaml") + ["autoscalers/csa-java-http/src/main/resources/application.yaml"]; parsed = files.to_h { |path| [path, YAML.safe_load_file(path, permitted_classes: [], aliases: false)] }; %w[h hq v vq].each { |tag| image = parsed.fetch("autoscalers/csa-java-http/custom-selfadapter-#{tag}.yaml").dig("spec", "template", "spec", "containers", 0, "image"); raise "mismatched tag: #{tag}" unless image.end_with?(":#{tag}") }; puts "Parsed #{files.length} YAML files; profile and manifest suffixes align"'
rg -n '[[:blank:]]+$' autoscalers/csa-java-http/.dockerignore autoscalers/csa-java-http/Dockerfile autoscalers/csa-java-http/profiles autoscalers/csa-java-http/custom-selfadapter-*.yaml autoscalers/csa-java-http/README.md docs/csa-java-http/historico-de-desenvolvimento.md
git diff --check
```

Os comandos planejados para uma validação futura, depois que o ciclo periódico consumir também as estratégias, são:

```sh
docker build -f autoscalers/csa-java-http/Dockerfile --build-arg PROFILE=h -t registry.k8s.lab/csa-znn-java-http:h autoscalers/csa-java-http
docker build -f autoscalers/csa-java-http/Dockerfile --build-arg PROFILE=hq -t registry.k8s.lab/csa-znn-java-http:hq autoscalers/csa-java-http
docker build -f autoscalers/csa-java-http/Dockerfile --build-arg PROFILE=v -t registry.k8s.lab/csa-znn-java-http:v autoscalers/csa-java-http
docker build -f autoscalers/csa-java-http/Dockerfile --build-arg PROFILE=vq -t registry.k8s.lab/csa-znn-java-http:vq autoscalers/csa-java-http
kubectl apply -f autoscalers/csa-java-http/custom-selfadapter-h.yaml
```

Eles não foram executados nesta etapa. O Spring Boot carrega as propriedades dos profiles selecionados, mas os campos de intervalo, limites e estratégias ainda não dirigem o ciclo periódico/avaliação; os perfis com CPU/tag também não têm adaptadores.

## Registro histórico: primeiro alinhamento dos profiles

Uma etapa anterior alinhou temporariamente os profiles Java HTTP ao formato dos profiles Python HTTP, incluindo `kubernetesMetricSpecs`, `requireKubernetesMetrics` e `logVerbosity`. Na revisão registrada abaixo, essa proposta foi substituída pelo schema menor das implementações HTTP independentes. A validação daquela etapa comparou os quatro YAMLs com os Python HTTP e executou `git diff --check`; esse comparativo não se aplica ao schema atual.

## Revisão do schema dos profiles Java HTTP

O schema foi simplificado para corresponder às implementações HTTP independentes: `interval`, `minReplicas`, `maxReplicas`, `maxCPU`, `metric` (`name`, `selector`, `targetValue`) e `enabled_strategies`. Os quatro profiles mantêm intervalo `5000`, réplicas de `1` a `5`, `maxCPU: 750` e métrica `znn_latency_ms_p95`, seletor vazio e alvo `1000`; só a lista de estratégias varia por H/HQ/V/VQ.

A decisão considera a fronteira do operador: a `CustomSelfAdapter` provisiona o Pod e o RBAC. O serviço independente será dono do ciclo de execução, portanto seus profiles não devem configurar callbacks do executor genérico antigo nem exigir um Service separado. Os blocos `kubernetesMetricSpecs`, `requireKubernetesMetrics` e `logVerbosity` foram removidos, assim como o wrapper de configuração específico do runtime anterior.

`src/main/resources/application.yaml` foi preservado, pois os serviços existentes ainda usam os valores `csa.*`. Embora o Dockerfile copie o profile escolhido para `/app/config/application.yaml`, o runtime ainda não consome o novo schema no ciclo CSA: não há carregamento de `/config.yaml` como configuração CSA nem loop periódico. A adaptação de réplicas é a única estratégia implementada; CPU e tag continuam pendentes.

### Comandos e validação

Os quatro arquivos foram parseados como YAML e verificados quanto ao conjunto de chaves, valores-base e estratégias por cenário. `git diff --check` passou. Não foram executados testes, build, criação de imagem ou comandos no cluster.

```sh
for f in autoscalers/csa-java-http/profiles/{h,hq,v,vq}.yaml; do cat "$f"; done
ruby -e 'require "yaml"; expected = %w[interval minReplicas maxReplicas maxCPU metric enabled_strategies].sort; strategies = {"h" => ["adapt_replicas"], "hq" => ["adapt_replicas", "adapt_tag"], "v" => ["adapt_cpu"], "vq" => ["adapt_cpu", "adapt_tag"]}; strategies.each { |tag, enabled| config = YAML.safe_load_file("autoscalers/csa-java-http/profiles/#{tag}.yaml", permitted_classes: [], aliases: false); raise "unexpected keys: #{tag}" unless config.keys.sort == expected; raise "unexpected base values: #{tag}" unless [config["interval"], config["minReplicas"], config["maxReplicas"], config["maxCPU"], config.dig("metric", "name"), config.dig("metric", "selector"), config.dig("metric", "targetValue")] == [5000, 1, 5, 750, "znn_latency_ms_p95", "", 1000]; raise "unexpected strategies: #{tag}" unless config["enabled_strategies"] == enabled }; puts "Validated four Java HTTP profiles"'
git diff --check
```

## Atividade 7 — callbacks HTTP pelo runtime genérico

### Decisões

- A aplicação Java e o runtime genérico são Pods separados gerenciados pelo operador. O runtime coleta a métrica e invoca a API Java pelo Service `csa-java-http-api`.
- Os profiles mantêm o schema de callbacks do runtime (`metric`, `evaluate`, `adapt`, `kubernetesMetricSpecs`, limites e intervalo) e apontam para o DNS do Service. Um ConfigMap por perfil é montado em `/config.yaml` no Pod do runtime.
- O Service seleciona a aplicação Java com as labels aplicadas pelo operador. Só o runtime consulta métricas Kubernetes; a API recebe o snapshot pelo HTTP.
- Sem decisão, `/evaluate` retorna `{}`. O runtime interpreta esse objeto e stdout vazio dos callbacks shell como avaliação sem estratégia, encerrando o ciclo com sucesso e sem chamar `Adapt`. Isso alinha o resultado dos dois protocolos e corrige a falha anterior ao decodificar stdout vazio.
- Somente `adapt_replicas` fica habilitado nos profiles desta etapa. As adaptações de CPU e tag ainda não estão implementadas.
- Os manifests usam a imagem `registry.k8s.lab/custom-self-adapter:alpine-empty-evaluation`, que depende da construção/publicação do runtime atualizado. Nenhuma imagem foi publicada nem recurso foi aplicado ao cluster.

### Validação e comandos

O teste Java falhou inicialmente no retorno sem decisão, passou depois que `/evaluate` passou a devolver `{}` e voltou a falhar quando a alteração foi revertida temporariamente. A suíte completa passou; os YAMLs foram parseados e o diff não contém erros de whitespace:

```sh
./gradlew test
../../.venv/bin/python -c 'from pathlib import Path; import yaml; base=Path("autoscalers/csa-java-http"); files=sorted(base.glob("profiles/*.yaml"))+sorted(base.glob("custom-selfadapter-*.yaml"))+[base/"service.yaml"]; [list(yaml.safe_load_all(path.read_text())) for path in files]; print(f"parsed {len(files)} YAML files")'
git diff --check
```

A suíte Gradle terminou com `BUILD SUCCESSFUL`; 10 YAMLs foram parseados. No worktree local do runtime, `go test ./...` passou após testes RED/GREEN independentes para parsear saída vazia/`{}` e pular adaptação. Não foram executados build ou testes no cluster.

## Correção do fluxo HTTP e dos profiles por cenário

### Decisões

- Os profiles preservam o schema do runtime usado pelas implementações shell: `metric`, `evaluate`, `adapt`, `interval`, limites, `kubernetesMetricSpecs`, `enabled_strategies`, `requireKubernetesMetrics` e `logVerbosity`. Os callbacks de métrica, avaliação e adaptação apontam para o Service Java.
- As estratégias habilitadas voltaram a variar por cenário: `h` usa réplicas; `hq` usa réplicas e tag; `v` usa CPU; `vq` usa CPU e tag. Os blocos de callback permanecem declarados como nas configurações shell.
- Runtime e API continuam em Pods separados, cada um criado por um `CustomSelfAdapter`. O Service da API continua sendo um manifesto explícito; o operador não foi alterado.
- Para não criar ConfigMap, `runtime.Dockerfile` deriva da imagem genérica existente e copia o profile de cenário para `/config.yaml`. Cada imagem recebe uma tag própria `csa-znn-java-http-runtime:<profile>`.
- Esta implementação ainda tem somente o callback de réplicas. `hq`, `v` e `vq` refletem corretamente a configuração tradicional, mas dependem dos callbacks CPU/tag antes de serem executados. O runtime genérico atual também pode registrar erro quando recebe uma avaliação sem estratégia; nenhum código do runtime externo foi alterado.
- Os manifests removem os mounts e volumes de ConfigMap. A API continua empacotando o mesmo profile na própria imagem para avaliar segundo o cenário escolhido.

### Inspeção, comandos e validação

Foram comparados os quatro profiles HTTP com `autoscalers/csa-go/profiles/` e `autoscalers/csa-java/profiles/`, e lidos os executores HTTP, a avaliação e o fluxo de adaptação do runtime externo em modo somente leitura. Também foram inspecionados os manifests e os Dockerfiles locais. Nenhum build, teste, push de imagem, alteração no runtime externo ou comando no cluster foi executado nesta correção.

Comandos de referência desta revisão:

```sh
rg --files autoscalers/csa-java-http docs/csa-java-http
cat autoscalers/csa-java-http/profiles/{h,hq,v,vq}.yaml
cat autoscalers/csa-java/profiles/{h,hq,v,vq}.yaml
sed -n '1,180p' ../custom-self-adapter/internal/selfadapter/selfadapter.go
sed -n '1,180p' ../custom-self-adapter/internal/evaluatecalc/evaluatecalc.go
git diff --check
```
