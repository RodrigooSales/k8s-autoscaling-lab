# Estrutura da implementação CSA Java

Este documento descreve os arquivos versionados em
[`autoscalers/csa-java`](../../autoscalers/csa-java/). O executável oferece as etapas
`metric`, `evaluate`, `adapt_replicas`, `adapt_cpu` e `adapt_tag`.

## Código Java

### Inicialização e entrada/saída

- [`App.java`](../../autoscalers/csa-java/src/main/java/org/csajava/App.java) é o ponto de entrada. Interpreta a CLI, prepara o contexto, chama o handler do modo e trata falhas do processo.
- [`cli/Cli.java`](../../autoscalers/csa-java/src/main/java/org/csajava/cli/Cli.java) valida os argumentos `-m <modo>` e produz o resultado do parsing.
- [`cli/Mode.java`](../../autoscalers/csa-java/src/main/java/org/csajava/cli/Mode.java) enumera os modos aceitos e converte os nomes recebidos pela CLI.
- [`context/RuntimeContext.java`](../../autoscalers/csa-java/src/main/java/org/csajava/context/RuntimeContext.java) reúne stdin bruto e parseado, configuração, hints e caminho do arquivo de configuração.
- [`io/Stdin.java`](../../autoscalers/csa-java/src/main/java/org/csajava/io/Stdin.java) lê toda a entrada padrão do processo.
- [`io/JsonOut.java`](../../autoscalers/csa-java/src/main/java/org/csajava/io/JsonOut.java) serializa resultados como JSON e os escreve no stdout.
- [`config/ConfigLoader.java`](../../autoscalers/csa-java/src/main/java/org/csajava/config/ConfigLoader.java) carrega YAML, usando o caminho solicitado e o fallback local definido pela implementação.
- [`logging/AdapterLogger.java`](../../autoscalers/csa-java/src/main/java/org/csajava/logging/AdapterLogger.java) fornece os níveis e destinos de log usados pelos modos.
- [`runtime/ModeHandler.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/ModeHandler.java) define a interface comum para executar um modo com `RuntimeContext`.
- [`runtime/ModeHandlers.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/ModeHandlers.java) associa cada modo ao runtime correspondente e encaminha resultados JSON ao stdout.

### Kubernetes e utilitários

- [`kubernetes/KubernetesClient.java`](../../autoscalers/csa-java/src/main/java/org/csajava/kubernetes/KubernetesClient.java) cria o cliente Kubernetes usando a configuração in-cluster.
- [`kubernetes/KubernetesJson.java`](../../autoscalers/csa-java/src/main/java/org/csajava/kubernetes/KubernetesJson.java) serializa objetos Kubernetes em JSON removendo valores padrão vazios usados nos corpos de patch.
- [`util/JsonUtil.java`](../../autoscalers/csa-java/src/main/java/org/csajava/util/JsonUtil.java) centraliza a leitura segura de objetos, arrays e valores em caminhos do JSON de entrada.
- [`util/YamlMap.java`](../../autoscalers/csa-java/src/main/java/org/csajava/util/YamlMap.java) extrai mapas, listas e valores tipados da configuração carregada em YAML.
- [`util/CpuQuantity.java`](../../autoscalers/csa-java/src/main/java/org/csajava/util/CpuQuantity.java) converte quantidades de CPU para millicores e formata o resultado para a API Kubernetes.

### Etapas do CSA e suporte às adaptações

- [`runtime/metric/MetricRuntime.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/metric/MetricRuntime.java) extrai da entrada as réplicas atuais e os valores atual e alvo da primeira métrica.
- [`runtime/evaluate/EvaluateRuntime.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java) calcula carga e seleciona a estratégia, aplicando prioridades, limites configurados e estado obtido do Kubernetes.
- [`runtime/adapt/AdaptSupport.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/adapt/AdaptSupport.java) reúne operações comuns aos adaptadores: identificação do Deployment, parâmetros, rollout, seleção de Pods, CPU e formatos de resultado.
- [`runtime/adapt/replicas/AdaptReplicasRuntime.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntime.java) aplica a quantidade de réplicas ao Deployment por patch estratégico.
- [`runtime/adapt/cpu/AdaptCpuRuntime.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntime.java) calcula os novos limites de CPU dentro do baseline e do máximo configurado e redimensiona os Pods.
- [`runtime/adapt/tag/AdaptTagRuntime.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/adapt/tag/AdaptTagRuntime.java) percorre a escada de tags da imagem e pode atualizar CPU no template do Deployment.
- [`runtime/initialdata/InitialDataStore.java`](../../autoscalers/csa-java/src/main/java/org/csajava/runtime/initialdata/InitialDataStore.java) lê o estado `initialData` do recurso CSA e grava tag ou CPU inicial pela anotação do Pod do adaptador.

## Testes Java

Os testes ficam em `src/test/java` e exercitam contratos, runtimes e chamadas Kubernetes sem depender de uma rodada de carga.

- [`AppTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/AppTest.java) verifica a execução da aplicação, resultados e erros do processo.
- [`cli/CliTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/cli/CliTest.java) cobre argumentos aceitos e erros de uso da CLI.
- [`config/BuildProfileTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/config/BuildProfileTest.java) verifica os perfis e as configurações incluídas nas imagens.
- [`config/ConfigTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/config/ConfigTest.java) fixa os valores e estratégias da configuração usados pelo experimento.
- [`context/RuntimeContextTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/context/RuntimeContextTest.java) verifica parsing do stdin, configuração e construção do contexto.
- [`io/JsonOutTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/io/JsonOutTest.java) cobre serialização e escrita dos resultados JSON.
- [`logging/AdapterLoggerTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/logging/AdapterLoggerTest.java) verifica o logger do adaptador.
- [`runtime/RuntimeLoggingTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/runtime/RuntimeLoggingTest.java) verifica os logs emitidos pelos runtimes.
- [`runtime/evaluate/EvaluateQuantityTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/runtime/evaluate/EvaluateQuantityTest.java) cobre a leitura e conversão das quantidades de métrica usadas na avaliação.
- [`runtime/initialdata/InitialDataStoreTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/runtime/initialdata/InitialDataStoreTest.java) verifica leitura, merge e persistência de `initialData`, inclusive atraso de reconciliação e falhas.
- [`runtime/adapt/replicas/AdaptReplicasRuntimeTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntimeTest.java) confere patch de réplicas, endpoint, payload e erros do adaptador.
- [`runtime/adapt/cpu/AdaptCpuRuntimeTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntimeTest.java) cobre cálculo e limites de CPU, rollout, resize e falhas intermediárias.
- [`runtime/adapt/tag/AdaptTagRuntimeTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/runtime/adapt/tag/AdaptTagRuntimeTest.java) cobre parsing e mudança de imagem, limites da escada, rollout e atualização opcional de CPU.
- [`util/CpuQuantityTest.java`](../../autoscalers/csa-java/src/test/java/org/csajava/util/CpuQuantityTest.java) verifica conversões entre quantidades Kubernetes e millicores.

## Configuração e empacotamento

- [`README.md`](../../autoscalers/csa-java/README.md) registra o estado da migração, arquitetura, paridade conhecida, testes e comandos de execução/build.
- [`build.gradle`](../../autoscalers/csa-java/build.gradle) declara dependências, JUnit, toolchain Java 21 e classe principal da aplicação.
- [`settings.gradle`](../../autoscalers/csa-java/settings.gradle) define o nome do projeto Gradle e a resolução da toolchain.
- [`gradlew`](../../autoscalers/csa-java/gradlew) inicia o Gradle pelo shell em sistemas POSIX usando a versão do wrapper.
- [`gradlew.bat`](../../autoscalers/csa-java/gradlew.bat) oferece o mesmo comando do wrapper para Windows.
- [`gradle/wrapper/gradle-wrapper.jar`](../../autoscalers/csa-java/gradle/wrapper/gradle-wrapper.jar) contém o bootstrap que baixa e inicia a distribuição Gradle definida pelo wrapper.
- [`gradle/wrapper/gradle-wrapper.properties`](../../autoscalers/csa-java/gradle/wrapper/gradle-wrapper.properties) fixa a distribuição Gradle usada pelo wrapper e seus caminhos de cache.
- [`.classpath`](../../autoscalers/csa-java/.classpath) descreve ao Eclipse os diretórios Java de produção e teste e o JRE 21.
- [`.project`](../../autoscalers/csa-java/.project) registra o projeto e os builders Java/Gradle para o Eclipse.
- [`.gitignore`](../../autoscalers/csa-java/.gitignore) exclui caches e saídas locais de build, além de configurações locais de IDE e ambientes Python.
- [`config.yaml`](../../autoscalers/csa-java/config.yaml) configura os modos, limites, timeouts, métrica e estratégias para execução local.
- [`Dockerfile`](../../autoscalers/csa-java/Dockerfile) compila a distribuição Java em uma etapa Gradle e monta a imagem runtime com o perfil escolhido.
- [`build_csa-java.sh`](../../autoscalers/csa-java/build_csa-java.sh) seleciona o perfil, constrói e publica a imagem e gera o manifesto da tag.

### Perfis

Os perfis configuram comandos, timeouts e estratégias habilitadas para cada variante.

- [`profiles/h.yaml`](../../autoscalers/csa-java/profiles/h.yaml) habilita adaptação horizontal por réplicas.
- [`profiles/hq.yaml`](../../autoscalers/csa-java/profiles/hq.yaml) habilita réplicas e adaptação de qualidade por tag.
- [`profiles/v.yaml`](../../autoscalers/csa-java/profiles/v.yaml) habilita adaptação vertical de CPU.
- [`profiles/vq.yaml`](../../autoscalers/csa-java/profiles/vq.yaml) habilita CPU e adaptação de qualidade por tag.

### Manifests do CustomSelfAdapter

Os manifests apontam para `kube-znn`, configuram o container Java e concedem permissão para `pods/resize`. Os arquivos por perfil apontam para as tags publicadas; o template recebe `IMAGE` e `TAG` no build.

- [`custom-selfadapter-h.yaml`](../../autoscalers/csa-java/custom-selfadapter-h.yaml) seleciona a imagem do perfil `h`.
- [`custom-selfadapter-hq.yaml`](../../autoscalers/csa-java/custom-selfadapter-hq.yaml) seleciona a imagem do perfil `hq`.
- [`custom-selfadapter-v.yaml`](../../autoscalers/csa-java/custom-selfadapter-v.yaml) seleciona a imagem do perfil `v`.
- [`custom-selfadapter-vq.yaml`](../../autoscalers/csa-java/custom-selfadapter-vq.yaml) seleciona a imagem do perfil `vq`.
- [`custom-selfadapter-template.yaml`](../../autoscalers/csa-java/custom-selfadapter-template.yaml) é o modelo usado para gerar um manifesto com o repositório e a tag escolhidos.
