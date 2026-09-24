# Entrega 1 — investigação das referências

Data: 2026-09-23. Revisão do laboratório: `3e0e43035a8766eafbbdf507bd25d1d238d3fa5d`.
Diretório dos comandos: `autoscalers/`, salvo indicação diferente.

## Escopo e evidência

Esta entrega contém somente investigação e documentação. As conclusões são observações
estáticas do código, não resultados de testes executados ou de medições de performance.
Não foram implementadas otimizações nem lógica Go.
Não foram executados builds, scripts de carga, consultas ao cluster ou benchmarks.

Python é a referência de comportamento. Java é uma referência adicional, com diferenças
que precisam permanecer visíveis. Nenhum arquivo das duas versões pode ser alterado nesta
branch. Fontes do runtime e do operador foram consultadas apenas para compreender o contrato.

As versões abaixo são **declarações dos arquivos**, não um inventário das imagens rodando:

| Componente | Declaração consultada |
|---|---|
| Python | Imagem base `custom-self-adapter:python-3-12-latest`; `kubernetes` e `pyyaml` sem versões fixadas em requirements.txt |
| Java | Toolchain 21; client-java 26.0.0; Gson 2.13.2; SnakeYAML 2.6; JUnit 4.13.2; slf4j-nop 2.0.17 |
| Gradle | Wrapper 9.3.0; Docker usa `gradle:jdk21`, portanto a versão do Gradle da imagem não está fixada pelo wrapper |
| Go | `go.mod`: 1.25.0; mise: 1.25; api/apimachinery/client-go 0.35.3; `go.yaml.in/yaml/v2` 2.4.3 |
| Laboratório | Guia declara Kubernetes 1.35.3 e Calico 3.31.4; cluster não consultado nesta entrega |

Fontes: [Python](../../csa/requirements.txt), [imagem Python](../../csa/Dockerfile),
[dependências Java](../../csa-java/build.gradle), [imagem Java](../../csa-java/Dockerfile),
[wrapper](../../csa-java/gradle/wrapper/gradle-wrapper.properties), [módulo Go](../go.mod).

O runtime local está na revisão `68642990d9b81c51c6cad3d60a123db525918469`;
o operador local, em `08de29d2c82d01790d9c036123f64ca479c798d5`.
Isso não prova que os mesmos binários estejam nas imagens do cluster.

## Documentos

- [Melhorias de performance](melhorias-de-performance.md): propostas e condições de aplicação nas três linguagens.
- [Paridade](paridade.md): comportamento observado e diferenças que não devem ser corrigidas silenciosamente.
- [Testes unitários](testes-unitarios.md): contratos compartilhados e cobertura unitária da implementação Go.
- [Integração e timeouts](integracao-e-timeouts.md): contratos do runtime/operador e condições do experimento.
- [Comandos da investigação](comandos-investigacao.md): comandos desta entrega, transcrição da pesquisa anterior e verificação documental.

Os detalhes permanecem nestes arquivos. O diálogo do agente deve conter apenas progresso
essencial, bloqueios e um resumo curto da entrega.

## Controle de entregas

| Entrega | Estado |
|---|---|
| 1. Investigação Python/Java e documentação | Concluída |
| 2. Contratos consumidos pelos testes Go | Concluída |
| 3. Entrada/saída, configuração, logs e métrica Go | Concluída |
| 4. Kubernetes, estado inicial e avaliação | Concluída |
| 5A. Adaptador de réplicas | Concluída |
| 5B. Adaptador de CPU | Concluída |
| 5C. Adaptador de tag | Concluída |
| 6. Empacotamento e validação funcional no cluster | Concluída |
| 7. Integração aos três scripts run_tests* | Concluída; validação estática aprovada |

Para manter cada PR próximo do limite de linhas de lógica, a entrega 5 foi dividida nas
subentregas 5A (réplicas), 5B (CPU) e 5C (tag). O usuário autorizou explicitamente 5B e 5C
em conjunto nesta solicitação; o ponto de parada será após ambas.
PRs devem ter aproximadamente 500 linhas de lógica de produção; testes, fixtures,
documentação e arquivos sem lógica de produção não entram nessa contagem.

## Limites para as próximas entregas

O cluster está disponível para verificações funcionais, depois de conferir o contexto.
Locust e as matrizes `run_tests*` ficam reservados ao usuário, salvo autorização expressa para
uma execução específica.
Não implementar simulador nem executor local de cenários. Testes unitários devem ser
independentes de cluster, rede externa e Docker.

As oportunidades de otimização deste levantamento não autorizam modificar o baseline Go
nem as referências. Eventuais experimentos otimizados ficam para outra branch, com política
de comparação explícita. Os contratos consumidos pelos testes Go ficam em
[contracts/cases](../../contracts/cases/index.json); eles definem os resultados esperados
pela referência Python.

## Verificações da entrega 1

Verificação documental aprovada: seis documentos, 105 links locais válidos, referências
de linha existentes e nenhum erro de whitespace. Os SHA-256 dos 76 arquivos versionados
Python/Java são idênticos aos do início; não há diff nesses diretórios contra HEAD.
As únicas escritas desta entrega foram os seis arquivos Markdown em csa-go/docs/.
Nenhuma suíte de testes foi executada, pois esta entrega não altera lógica nem testes.
As 12 oportunidades de performance são hipóteses apoiadas em leitura de código, sem ganho
medido. As pendências de caracterização estão registradas em paridade e testes-unitarios.

O estado inicial já incluía alterações em `../AGENTS.md`, no submódulo de infraestrutura
e o scaffold `csa-go/` não rastreado. Essas alterações não pertencem a esta entrega.

## Resultado da entrega 2

Criado o corpus compartilhado em contracts/cases/: casos indexados para métricas, avaliação,
conversão de CPU e adaptações Go. Cada caso contém entrada, configuração, estado e resultado
esperado segundo Python.

A validação estrutural confirmou JSON válido, IDs únicos, índice completo e campos
obrigatórios em todos os casos. A implementação e os testes Go ainda não foram iniciados.
Nenhum arquivo de csa/ ou csa-java/ foi modificado.

Ponto de parada alcançado após concluir a entrega 2. A entrega 3 foi autorizada em solicitação posterior.

## Resultado da entrega 3

Implementados o parsing da CLI, o carregador YAML, logs síncronos em stderr e
`/tmp/adapter.log`, e o modo `metric`. A métrica lê somente a primeira entrada de
`kubernetesMetrics`, preserva os tipos JSON dos três campos de saída e não acessa
configuração nem Kubernetes. Os modos restantes são reconhecidos pela CLI e ainda
aguardam suas entregas específicas.

Os testes Go consomem os dois contratos `metric` e cobrem argumentos inválidos, JSON
inválido, sinks de log, YAML válido/vazio/nulo/inválido e erro de leitura de arquivo.
`GOCACHE=/tmp/csa-go-gocache go test ./... -count=1` e o build do executável passaram.
Os testes de contrato falharam quando a implementação de métrica foi temporariamente
retirada; os testes de configuração falharam quando o carregador foi temporariamente
retirado. Após restaurar cada implementação, a suíte passou novamente.

Arquivos de produção adicionados/alterados: main, métrica, logger, configuração, módulo Go
e README. Testes: `main_test.go` e `config_test.go`. Python e Java não foram alterados.
Nenhum teste de referência externa, teste de carga ou consulta ao cluster foi executado.

Após solicitação posterior, os fontes Go foram organizados em `cmd/csa-go/` e
`internal/csa/`; os testes acompanham o pacote interno. O ponto de entrada delega ao
runtime interno sem alterar a lógica da entrega. Os casos de contrato que exercitam
`metricResult` diretamente foram separados em `internal/csa/metric_test.go`.

O ponto de parada da entrega 3 foi encerrado por solicitação posterior do usuário; o
resultado da entrega 4 está registrado abaixo.

## Resultado da entrega 4

Implementados os contratos Go de conversão de quantidades e seleção da estratégia, o
cliente Kubernetes in-cluster usado pela avaliação, consulta de Deployment/Pods/CR status,
persistência de CPU inicial pela anotação do Pod CSA e emissão do resultado `evaluate`.
O cliente mantém a distinção entre `resources` ausente e vazio nas respostas unstructured;
não adiciona chamadas Kubernetes para capturar esse detalhe.

A sequência testada é Deployment → Pods → status e, sem baseline, releitura de status → Pod
do CSA → status → patch. Os casos cobrem avaliação horizontal consultando CPU, saída vazia,
falhas Kubernetes, JSON de estado inválido, merge de initialData, baseline local zero até a
reconciliação, Pod sem annotations, seleção do máximo entre Pods e patch estratégico.
Os testes usam `client-go` fake e doubles; não abrem sockets, não acessam cluster, rede
externa ou Docker.

`GOCACHE=/tmp/csa-go-gocache go test ./... -count=1`, `go vet ./...` e build do executável
passaram. Nenhum teste de carga foi executado. A integração implantada/runtime/operador e a
validação no cluster continuam para a entrega 6. Naquele ponto, as adaptações
`adapt_replicas`, `adapt_cpu` e `adapt_tag` ainda não tinham sido iniciadas.

O README Go e o `AGENTS.md` foram corrigidos para registrar a estrutura `cmd/`/`internal/`,
os modos já disponíveis, as dependências atuais e os comandos de build válidos.

Ponto de parada da entrega 4 foi encerrado por autorização explícita. A subentrega 5A foi
concluída e as subentregas 5B (CPU) e 5C (tag) foram autorizadas em conjunto.

## Resultado da subentrega 5A — adaptador de réplicas

O modo `adapt_replicas` agora lê o Deployment antes de validar `evaluation.parameters.replicas`,
constrói o corpo completo com apenas `spec.replicas` atualizado e envia strategic merge patch.
Sucesso retorna `{"replicas": valor}`; falha no patch retorna `{"result":"error"}`. Entrada
inválida ou falha ao criar contexto/ler Deployment mantém stdout vazio. O comportamento Python
de `bool` como subtipo de `int` também foi preservado no resultado e no corpo JSON.

O fixture compartilhado `adapt_replicas_success` documenta entrada, Deployment carregado e
resultado esperado. Testes unitários verificam ordem GET/PATCH, campos preservados, tipo e
conteúdo do patch, validação, saída, falhas e operação sem ler `/config.yaml`. O cliente fake
oficial de `client-go` confirma `StrategicMergePatchType`. Os testes não acessam cluster,
rede externa, Docker ou carga.

A implementação ficou abaixo do limite aproximado de 500 linhas de lógica. `go test ./...`,
`go vet ./...` e build passaram. O RED foi repetido com a implementação substituída
temporariamente por retorno não implementado; os testes do adaptador falharam e passaram
novamente após restaurar o código.

## Resultado das subentregas 5B e 5C — adaptadores de CPU e tag

O modo `adapt_cpu` preserva a ordem de validação e a checagem de rollout, carrega o limite
inicial do estado CSA ou do Deployment, mantém as duas listagens de Pods, calcula o máximo
de CPU atual e aplica o arredondamento Python (ties-to-even) e os limites configurados. Envia
os limites `znn` e `nginx` sequencialmente pelo subrecurso `pods/resize`, parando no primeiro
erro sem rollback. Inteiros intermediários sem limite de `int64` preservam o comportamento
Python para multiplicadores finitos extremos antes da aplicação dos limites. Os logs registram
os limites aplicados.

O modo `adapt_tag` preserva a escada `100k`–`800k`, o limite de tag inicial ao subir, a
persistência de `initialData`, as condições de rollout e o comportamento opcional de
`update_cpu`. O patch do Deployment conserva o objeto carregado, troca a imagem e, quando
solicitado, atualiza os limites de CPU dos containers. O parser Go cobre as formas de imagem
aceitas pelo regex Python, inclusive registry e porta.

Os fixtures `adapt_cpu_success` e `adapt_tag_success` ampliam o corpus para 19 casos. Os testes
verificam saída JSON, ordens de chamadas, corpos e tipo dos patches, arredondamento e limites,
primeira falha de resize, tags inválidas/ausentes, rollout, dados iniciais e `update_cpu`.
`go test ./...`, `go vet ./...`, build e `gofmt` passaram. RED → GREEN → RED → GREEN foi
confirmado separadamente nos dois adaptadores. Nenhum cluster, teste de carga, Locust ou
script `run_tests*` foi executado. Python e Java permaneceram sem alterações.

As subentregas 5B e 5C foram autorizadas juntas e concluídas.

## Resultado da entrega 6 — empacotamento e validação funcional

O Dockerfile usa a imagem runtime já disponível no Docker e fixa seu digest. O script
`build_csa-go.sh` compila o binário Go estático para linux/amd64, publica as imagens h, hq, v
e vq em `registry.k8s.lab/csa-znn-go` e gera um manifesto próprio para cada perfil. Os quatro
perfis preservam intervalo, limites, estratégias e métrica; os timeouts provisórios seguem os
valores Python. Recursos do Pod CSA, `pods/resize`, node selector e alvo seguem os manifests
de referência. Imagens, digests, comandos e resultados estão em
[validacao-funcional.md](validacao-funcional.md).

O cluster primeiro criou o runtime pelo operador em namespace isolado. Após solicitação do
usuário, os manifests h, hq e vq foram aplicados em `default`, com o alvo real `kube-znn`.
Chamadas autenticadas testaram avaliação, fallback entre réplicas e tag, CPU e
`status.initialData`; a métrica externa estava vazia, então a adaptação automática aguardou
dados. Os recursos temporários e o CR Go foram removidos, e a especificação de
`default/kube-znn` voltou aos valores iniciais. A identidade do Pod e geração do Deployment
mudaram nos rollouts funcionais. Nenhum Locust, carga ou script `run_tests*` foi executado.
Python e Java não foram alterados.

Entregas 6 e 7 concluídas. Os scripts de carga incluem CSA Go e passaram pela validação
estática. Repetições das matrizes, coleta comparativa dos CSVs e confirmação dos timeouts
continuam reservadas ao usuário.

Após autorização expressa posterior, foi executada uma iteração apenas do seletor `csa-go`,
cobrindo h, hq 25%, hq 50%, v e vq. Resumos, CSVs, adaptações observadas e estado restaurado
estão em [execucao-carga.md](execucao-carga.md). Nenhuma execução Python/Java foi iniciada.
