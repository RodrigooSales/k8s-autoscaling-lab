# Estrutura da implementação CSA Go

Este documento descreve a função de cada arquivo versionado em
[`autoscalers/csa-go`](../../autoscalers/csa-go/). O executável recebe uma etapa por
processo: `metric`, `evaluate`, `adapt_replicas`, `adapt_cpu` ou `adapt_tag`.

## Código Go

### Entrada e fluxo do processo

- [`cmd/csa-go/main.go`](../../autoscalers/csa-go/cmd/csa-go/main.go) inicia o programa e entrega os argumentos ao pacote interno.
- [`internal/csa/app.go`](../../autoscalers/csa-go/internal/csa/app.go) interpreta `-m`, lê stdin e encaminha a execução para o modo solicitado.
- [`internal/csa/logger.go`](../../autoscalers/csa-go/internal/csa/logger.go) centraliza os registros gravados em arquivo e enviados a stderr.
- [`internal/csa/config.go`](../../autoscalers/csa-go/internal/csa/config.go) lê o YAML de configuração do CSA e reporta erros de leitura ou parsing.

### Métrica e avaliação

- [`internal/csa/metric.go`](../../autoscalers/csa-go/internal/csa/metric.go) extrai a primeira métrica da entrada e produz o JSON usado pela avaliação, preservando os tipos JSON recebidos.
- [`internal/csa/evaluate.go`](../../autoscalers/csa-go/internal/csa/evaluate.go) aplica limiares, limites e prioridade das estratégias para decidir qual adaptação executar.
- [`internal/csa/evaluate_runtime.go`](../../autoscalers/csa-go/internal/csa/evaluate_runtime.go) conecta a decisão à configuração e ao Kubernetes: lê Deployment, Pods e estado inicial, então escreve a estratégia selecionada no stdout.
- [`internal/csa/cpu_quantity.go`](../../autoscalers/csa-go/internal/csa/cpu_quantity.go) interpreta quantidades de CPU e as converte para millicores.
- [`internal/csa/initial_data.go`](../../autoscalers/csa-go/internal/csa/initial_data.go) lê `status.initialData` do CSA e grava valores iniciais na anotação do Pod para reconciliação pelo operador.
- [`internal/csa/kubernetes.go`](../../autoscalers/csa-go/internal/csa/kubernetes.go) encapsula os acessos à API Kubernetes, incluindo leitura e patch de recursos, listagem de Pods e subrecurso `resize`.

### Adaptadores

- [`internal/csa/adapt_replicas.go`](../../autoscalers/csa-go/internal/csa/adapt_replicas.go) aplica a quantidade de réplicas recebida pela avaliação ao Deployment.
- [`internal/csa/adapt_cpu.go`](../../autoscalers/csa-go/internal/csa/adapt_cpu.go) calcula o novo limite de CPU respeitando baseline e máximo configurado, e redimensiona os Pods.
- [`internal/csa/adapt_tag.go`](../../autoscalers/csa-go/internal/csa/adapt_tag.go) percorre a escada de tags da imagem do workload e, quando solicitado, atualiza também CPU no template do Deployment.

## Testes Go

Os testes usam os contratos compartilhados em `autoscalers/contracts/cases/` e APIs falsas; não precisam de um cluster Kubernetes.

- [`adaptation_contract_test.go`](../../autoscalers/csa-go/internal/csa/adaptation_contract_test.go) carrega fixtures de adaptação e monta estado inicial e cliente Kubernetes de teste.
- [`app_test.go`](../../autoscalers/csa-go/internal/csa/app_test.go) verifica argumentos e modos da CLI, além do tratamento da entrada de métrica.
- [`config_test.go`](../../autoscalers/csa-go/internal/csa/config_test.go) cobre YAML válido, vazio, nulo, malformado e erros de arquivo.
- [`cpu_quantity_test.go`](../../autoscalers/csa-go/internal/csa/cpu_quantity_test.go) compara a conversão de CPU com os contratos compartilhados.
- [`metric_test.go`](../../autoscalers/csa-go/internal/csa/metric_test.go) compara a saída do modo `metric` com os contratos, incluindo tipos JSON.
- [`evaluate_test.go`](../../autoscalers/csa-go/internal/csa/evaluate_test.go) verifica decisões de avaliação e conversão dos valores das métricas.
- [`evaluate_runtime_test.go`](../../autoscalers/csa-go/internal/csa/evaluate_runtime_test.go) verifica a sequência de chamadas Kubernetes, seleção da CPU atual e persistência do baseline durante `evaluate`.
- [`initial_data_test.go`](../../autoscalers/csa-go/internal/csa/initial_data_test.go) cobre leitura, merge e gravação do estado inicial, inclusive erros e dados ausentes.
- [`kubernetes_test.go`](../../autoscalers/csa-go/internal/csa/kubernetes_test.go) verifica recursos Kubernetes usados, conversões, patches estratégicos e `pods/resize`.
- [`adapt_replicas_test.go`](../../autoscalers/csa-go/internal/csa/adapt_replicas_test.go) cobre validação da entrada, patch do Deployment e resultado do modo `adapt_replicas`.
- [`adapt_cpu_test.go`](../../autoscalers/csa-go/internal/csa/adapt_cpu_test.go) verifica cálculo, limites, rollout, listagem de Pods e sequência de redimensionamento.
- [`adapt_tag_test.go`](../../autoscalers/csa-go/internal/csa/adapt_tag_test.go) verifica parsing e troca de tags, limites da escada, rollout e atualização opcional de CPU.

## Configuração e empacotamento

- [`README.md`](../../autoscalers/csa-go/README.md) reúne dependências, comandos de desenvolvimento, fluxo de build e referências de comportamento.
- [`go.mod`](../../autoscalers/csa-go/go.mod) declara o módulo Go e suas dependências diretas e indiretas.
- [`go.sum`](../../autoscalers/csa-go/go.sum) registra os checksums das dependências resolvidas.
- [`mise.toml`](../../autoscalers/csa-go/mise.toml) fixa a versão de Go usada pelo projeto.
- [`.gitignore`](../../autoscalers/csa-go/.gitignore) exclui artefatos locais de build, como o diretório `bin/`.
- [`Dockerfile`](../../autoscalers/csa-go/Dockerfile) monta a imagem final com o binário e o perfil selecionado, usando a imagem runtime fixada por digest.
- [`build_csa-go.sh`](../../autoscalers/csa-go/build_csa-go.sh) compila o binário Linux estático, constrói e publica a imagem, e gera o manifesto para a tag escolhida.

### Perfis

Os perfis definem os comandos e tempos limite das etapas, o intervalo de avaliação e as estratégias habilitadas. Todos usam a métrica externa `znn_latency_ms_p95`.

- [`profiles/h.yaml`](../../autoscalers/csa-go/profiles/h.yaml) habilita adaptação horizontal por réplicas.
- [`profiles/hq.yaml`](../../autoscalers/csa-go/profiles/hq.yaml) habilita réplicas e adaptação da qualidade da imagem por tag.
- [`profiles/v.yaml`](../../autoscalers/csa-go/profiles/v.yaml) habilita adaptação vertical de CPU.
- [`profiles/vq.yaml`](../../autoscalers/csa-go/profiles/vq.yaml) habilita CPU e adaptação da qualidade da imagem por tag.

### Manifests do CustomSelfAdapter

Os manifests apontam para `kube-znn`, configuram o container CSA Go e concedem permissão para `pods/resize`. Os quatro arquivos fixam a tag da imagem; o template recebe `IMAGE` e `TAG` durante o build.

- [`custom-selfadapter-h.yaml`](../../autoscalers/csa-go/custom-selfadapter-h.yaml) usa a imagem com perfil horizontal `h`.
- [`custom-selfadapter-hq.yaml`](../../autoscalers/csa-go/custom-selfadapter-hq.yaml) usa a imagem com perfil horizontal e qualidade `hq`.
- [`custom-selfadapter-v.yaml`](../../autoscalers/csa-go/custom-selfadapter-v.yaml) usa a imagem com perfil vertical `v`.
- [`custom-selfadapter-vq.yaml`](../../autoscalers/csa-go/custom-selfadapter-vq.yaml) usa a imagem com perfil vertical e qualidade `vq`.
- [`custom-selfadapter-template.yaml`](../../autoscalers/csa-go/custom-selfadapter-template.yaml) é o modelo usado para gerar o manifesto com a imagem e a tag do build.

O binário em `bin/csa-go` é gerado pelo script de build e não faz parte dos arquivos versionados da implementação.
