# Diferenças entre CSA Python e CSA Java

## Objetivo

Este documento registra as diferenças encontradas entre as implementações em
`autoscalers/csa` e `autoscalers/csa-java`.

O objetivo futuro é equiparar configuração, comportamento e execução
experimental para que a variável independente dos benchmarks seja a linguagem
de implementação, e não diferenças de algoritmo, integração ou infraestrutura.

## Conclusão

No estado atual do repositório, não é possível afirmar que a única diferença
entre os testes seja a linguagem.

As implementações compartilham o mesmo objetivo e o núcleo da política de
adaptação, mas diferem em:

- configuração;
- chamadas à API Kubernetes;
- arredondamento;
- inicialização e persistência de estado;
- comportamento diante de erros;
- logging;
- imagens e dependências;
- ciclo de vida dos cenários;
- cobertura e natureza dos testes de paridade.

Os resultados atuais devem ser descritos como uma comparação entre duas
implementações do autoscaler. Eles ainda não isolam Python e Java como única
variável experimental.

## Classificação da paridade atual

| Dimensão | Estado | Observação |
|---|---|---|
| Objetivo do controlador | Equivalente | Ambas implementam métrica, avaliação e adaptação de réplicas, CPU e tag. |
| Métrica no caminho válido | Equivalente | Mesmo payload de entrada e saída para os casos esperados. |
| Política nominal de avaliação | Quase equivalente | Mesmos thresholds e prioridade, com diferenças de estado inicial e casos de borda. |
| Adaptação de CPU | Quase equivalente | Mesmo subresource e limites, mas arredondamento e falhas diferem. |
| Adaptação de réplicas | Resultado equivalente | O padrão de chamadas Kubernetes é diferente. |
| Adaptação de tag | Parcialmente equivalente | Parser, persistência e operação Kubernetes diferem. |
| Configuração usada atualmente | Não equivalente | `maxCPU`, estratégias e timeouts são diferentes. |
| Tratamento de erros | Não equivalente | Saída JSON, ausência de saída e códigos de processo diferem. |
| Logging | Não equivalente | Python grava em arquivo e stderr; Java quase não registra o caminho normal. |
| Pipeline experimental | Não equivalente | Há cenários com reset e persistência de estado diferentes. |
| Teste automatizado cruzado | Inexistente | Os testes Java não executam o Python real. |
| Reprodutibilidade das imagens | Insuficiente | Tags mutáveis, builds não parametrizados corretamente e dependências não totalmente fixadas. |

## 1. Configuração

Arquivos:

- `autoscalers/csa/config.yaml`
- `autoscalers/csa-java/config.yaml`

### 1.1 Diferenças confirmadas

| Parâmetro | Python | Java | Impacto esperado |
|---|---:|---:|---|
| `metric.timeout` | `1000` | `1000` | Nenhum. |
| `evaluate.timeout` | `2000` | `5000` | Java recebe 2,5 vezes mais tempo para concluir a avaliação. |
| `adapt.*.timeout` | `2000` | `5000` | Java recebe 2,5 vezes mais tempo para concluir uma adaptação. |
| `interval` | `5000` | `5000` | Nenhum. |
| `minReplicas` | `1` | `1` | Nenhum. |
| `maxReplicas` | `5` | `5` | Nenhum. |
| `maxCPU` | `750` | `1000` | Trajetórias verticais e momento do fallback para tag são diferentes. |
| `enabled_strategies` | `adapt_cpu`, `adapt_tag` | `adapt_cpu` | Python pode adaptar tag; Java não pode com a configuração atual. |
| Métrica externa | `znn_latency_ms_p95` | `znn_latency_ms_p95` | Igual. |
| Target | `1000` | `1000` | Igual. |
| `requireKubernetesMetrics` | `true` | `true` | Igual. |
| `logVerbosity` | `4` | `4` | Igual no controlador, mas não iguala o logging interno das implementações. |

Evidências:

- Python: `autoscalers/csa/config.yaml:1-54`.
- Java: `autoscalers/csa-java/config.yaml:1-59`.

### 1.2 Consequência experimental

Com `maxCPU: 750`, o Python atinge o limite vertical mais cedo. Se tag estiver
habilitada, ele pode mudar a qualidade após atingir esse limite. Com
`maxCPU: 1000` e apenas CPU habilitada, o Java pode continuar aumentando CPU e
nunca selecionar tag.

Mesmo que o código fosse semanticamente idêntico, essas configurações
produziriam decisões e desempenho da aplicação diferentes.

### 1.3 Equiparação necessária

- [ ] Definir uma configuração canônica por perfil.
- [ ] Usar os mesmos `minReplicas`, `maxReplicas` e `maxCPU`.
- [ ] Usar a mesma lista e ordem de estratégias.
- [ ] Usar o mesmo intervalo.
- [ ] Decidir se os timeouts fazem parte da condição experimental.
- [ ] Se o timeout não for a variável estudada, usar o mesmo valor.
- [ ] Registrar a configuração efetiva de cada Pod junto aos resultados.

## 2. Perfis `h`, `hq`, `v` e `vq`

### 2.1 Intenção dos perfis

Os nomes sugerem os seguintes comportamentos:

| Perfil | Estratégias esperadas |
|---|---|
| `h` | `adapt_replicas` |
| `hq` | `adapt_replicas`, `adapt_tag` |
| `v` | `adapt_cpu` |
| `vq` | `adapt_cpu`, `adapt_tag` |

Porém, os manifests apenas escolhem uma tag de imagem diferente. A configuração
fica embutida na imagem em `/config.yaml`.

### 2.2 Build não reproduz os quatro perfis

Os dois scripts definem incondicionalmente:

```bash
export TAG="vq"
```

Arquivos:

- `autoscalers/csa/build_csa-python.sh:3-9`;
- `autoscalers/csa-java/build_csa-java.sh:3-9`.

Consequências:

- `TAG=h ./build_csa-java.sh` continua construindo `vq`;
- a documentação Java que recomenda esse comando não corresponde ao script;
- não existe uma fonte explícita e versionada da configuração de cada perfil;
- imagens antigas podem conter configurações diferentes da configuração atual;
- não é possível reconstruir com segurança o artefato usado em um resultado
  antigo.

### 2.3 Equiparação necessária

- [ ] Fazer o script respeitar `TAG` fornecida externamente.
- [ ] Rejeitar tags que não sejam `h`, `hq`, `v` ou `vq`.
- [ ] Tornar a configuração de cada perfil explícita e versionada.
- [ ] Construir Python e Java a partir da mesma definição de perfil.
- [ ] Registrar Git SHA, configuração e digest da imagem.
- [ ] Usar digest nos manifests finais do experimento.

## 3. Manifests Kubernetes

Arquivos comparados:

- `custom-selfadapter-template.yaml`;
- `custom-selfadapter-h.yaml`;
- `custom-selfadapter-hq.yaml`;
- `custom-selfadapter-v.yaml`;
- `custom-selfadapter-vq.yaml`.

Após normalizar o repositório da imagem, todos os manifests Python e Java são
textualmente iguais.

### 3.1 Elementos equivalentes

- mesmo nome e namespace do CustomSelfAdapter;
- mesmo `nodeSelector`;
- mesmos requests: `128m` de CPU e `128Mi` de memória;
- mesmos limits: `1024m` de CPU e `1024Mi` de memória;
- mesmo Deployment alvo: `kube-znn`;
- mesma permissão para `pods/resize`;
- mesmo `imagePullPolicy: Always`.

### 3.2 Diferença

- Python usa `registry.k8s.lab/csa-znn:<perfil>`;
- Java usa `registry.k8s.lab/csa-znn-java:<perfil>`.

### 3.3 Risco de reprodutibilidade

As imagens são referenciadas por tags mutáveis e usam
`imagePullPolicy: Always`. Uma nova publicação com a mesma tag muda o artefato
do experimento sem mudar o manifest.

### 3.4 Equiparação necessária

- [ ] Publicar imagens imutáveis.
- [ ] Registrar ou usar o digest em cada execução.
- [ ] Verificar que requests, limits, node e ServiceAccount são idênticos.
- [ ] Aguardar o mesmo estado de readiness antes de iniciar carga.

## 4. Arquitetura de execução

### 4.1 Elementos equivalentes

As duas versões são executadas pelo mecanismo `shell` do CSA. Em cada fase, o
controlador cria um processo novo:

- Python inicia um interpretador e um script específico;
- Java inicia uma JVM e seleciona o modo com `-m`.

Isso mantém o modelo geral de invocação equivalente.

### 4.2 Diferença conceitual do benchmark

O experimento mede cold start em cada fase. Ele não mede Java em uma JVM
persistente e aquecida.

Essa escolha pode ser válida, mas a conclusão precisa ser descrita como
desempenho das linguagens dentro do contrato shell do CSA, incluindo:

- inicialização do runtime;
- carregamento de bibliotecas;
- leitura de configuração;
- criação dos clientes Kubernetes;
- serialização de JSON;
- encerramento do processo.

Se a intenção for medir apenas o algoritmo de decisão, será necessário um
harness diferente ou processos persistentes equivalentes.

## 5. Fase `metric`

Arquivos:

- Python: `autoscalers/csa/scripts/metric_nginx_req_duration.py`;
- Java: `autoscalers/csa-java/src/main/java/org/csajava/runtime/metric/MetricRuntime.java`.

### 5.1 Caminho válido equivalente

Ambos leem:

- `resource.spec.replicas`;
- `kubernetesMetrics[0].spec.external.target.value`;
- `kubernetesMetrics[0].external.current.value`.

Ambos produzem:

```json
{
  "current_replicas": 2,
  "target_value": "1000",
  "current_value": "1300000"
}
```

### 5.2 Entradas inválidas

Python usa indexação direta. Chaves ausentes podem gerar `KeyError`, encerrar o
processo e não produzir JSON.

Java verifica os campos e retorna `ResultError`, normalmente mantendo o processo
com saída JSON válida.

### 5.3 Coerção de tipos

O Java usa métodos Gson como `getAsInt()` e `getAsString()`, que podem aceitar
algumas primitivas coercíveis. O Python preserva mais diretamente o tipo vindo
de `json.loads`.

Para os payloads válidos atuais, isso não altera o resultado. Em casos inválidos
ou inesperados, o comportamento difere.

### 5.4 Equiparação necessária

- [ ] Definir o comportamento para campos ausentes.
- [ ] Definir stdout e código de saída em caso de erro.
- [ ] Definir se coerção de string para número é permitida.
- [ ] Criar casos diferenciais para payload vazio, campo ausente e tipo errado.

## 6. Fase `evaluate`

Arquivos:

- Python: `autoscalers/csa/scripts/evaluate.py`;
- Java: `autoscalers/csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java`.

### 6.1 Política nominal equivalente

As duas versões implementam:

```text
rate = current_value / (target_value * 1000)
```

Regras:

- `rate >= 0.95`: considera aumento/adaptação para alta carga;
- `rate < 0.90`: considera redução/restauração para baixa carga;
- `0.90 <= rate < 0.95`: não adapta;
- prioridade: réplicas, CPU e tag;
- réplicas desejadas: `ceil(current_replicas * rate)`;
- limites de réplicas e CPU vêm da configuração.

### 6.2 Baseline inicial de CPU diferente

No Python:

```python
initial_cpu_data = get_stored_cpu_limit() or 0
if not initial_cpu_data:
    spec_mcpu = ctx.get_spec_mcpu()
    store_cpu_limit(spec_mcpu)
initial_mcpu = int(initial_cpu_data)
```

O valor é armazenado, mas `initial_cpu_data` continua `0` durante aquela
execução.

No Java, quando o valor não existe, o valor lido da spec é atribuído a
`initialMcpu` e usado imediatamente.

Impacto na primeira avaliação sob baixa carga:

- Python pode entender que a CPU atual está acima de um baseline igual a zero e
  selecionar `adapt_cpu`;
- Java compara a CPU atual com o baseline real e pode não adaptar ou selecionar
  tag.

### 6.3 Estratégias vazias

No Python, `enabled_strategies: []` deixa todas as estratégias desabilitadas.

No Java, uma lista vazia é tratada como configuração ausente e ativa
`adapt_replicas` como fallback.

### 6.4 Estratégias inválidas

O Java filtra elementos que não sejam strings e pode ativar réplicas como
fallback quando nenhum elemento válido permanece. O Python faz testes de
pertencimento diretamente sobre o valor carregado do YAML.

### 6.5 Falha ao ler Kubernetes

O Python cria `AdaptContext`, carrega a configuração in-cluster e exige um
Deployment válido antes de avaliar.

O Java captura várias exceções em `resolveHints()` e devolve mapa vazio. Depois,
`current_mcpu` e `initial_mcpu` recebem zero.

Isso permite que o Java ainda selecione uma estratégia com estado incompleto.
A adaptação selecionada pode falhar na fase seguinte. O Python tende a encerrar
antes, sem decisão.

### 6.6 Parser de quantidades

Python usa `kubernetes.utils.quantity.parse_quantity`.

Java possui parser próprio em `EvaluateRuntime.parseQuantityInt()`.

Os valores usuais do experimento são equivalentes, mas o conjunto completo de
sufixos, overflow, precisão e entradas inválidas não é necessariamente igual.

### 6.7 Precisão do multiplicador

O Python serializa o `float` calculado.

O Java normaliza `Double` para até 12 casas decimais antes da serialização.

Com os valores atuais, o efeito tende a ser pequeno, mas a saída não é
formalmente idêntica para todas as razões possíveis.

### 6.8 Ausência de adaptação

O código atual está equivalente no caminho normal:

- Python não escreve stdout;
- Java retorna `null` e `ModeHandlers` não escreve stdout.

O `autoscalers/csa-java/README.md:38` ainda afirma que o Java retorna `skip` nesse
caso. Essa documentação está desatualizada em relação ao código.

### 6.9 Equiparação necessária

- [ ] Corrigir ou formalizar a primeira leitura do baseline.
- [ ] Igualar a semântica de lista vazia e ausente.
- [ ] Igualar o comportamento quando Kubernetes não responde.
- [ ] Compartilhar casos de teste para todas as quantidades aceitas.
- [ ] Definir precisão e serialização do multiplicador.
- [ ] Atualizar a documentação Java sobre ausência de adaptação.

## 7. Adaptação de réplicas

Arquivos:

- Python: `autoscalers/csa/scripts/adapt_replicas.py`;
- Java: `autoscalers/csa-java/src/main/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntime.java`.

### 7.1 Efeito esperado equivalente

Ambos aplicam o valor de `evaluation.parameters.replicas` em
`Deployment.spec.replicas`.

### 7.2 Sequência Kubernetes diferente

Python:

1. `AdaptContext` faz GET do Deployment.
2. Altera `deployment.spec.replicas` no objeto local.
3. Envia o objeto do Deployment em PATCH.

Java:

1. Não faz GET do Deployment.
2. Constrói `{"spec":{"replicas":N}}`.
3. Envia um PATCH mínimo.

### 7.3 Impacto

- uma chamada HTTP adicional no Python;
- payload maior no Python;
- serialização e desserialização adicionais;
- comportamento de concorrência potencialmente diferente;
- consumo de CPU e latência não comparáveis apenas pela linguagem.

### 7.4 Erros diferentes

- parâmetro inválido pode resultar em ausência de stdout no Python;
- Java retorna `{"result":"error"}`;
- erro de API Java pode incluir `message`;
- Python normalmente retorna apenas `{"result":"error"}` no erro de patch.

### 7.5 Equiparação necessária

- [ ] Escolher GET + PATCH ou PATCH mínimo para ambas.
- [ ] Usar o mesmo tipo de patch e payload semanticamente idêntico.
- [ ] Igualar erros, stdout e código de saída.
- [ ] Verificar a sequência HTTP em teste automatizado.

## 8. Adaptação de CPU

Arquivos:

- Python: `autoscalers/csa/scripts/adapt_cpu.py`;
- Java: `autoscalers/csa-java/src/main/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntime.java`.

### 8.1 Comportamento equivalente

As duas versões:

- exigem `cpu_multiplier` numérico;
- não adaptam durante rollout;
- leem o baseline inicial;
- listam Pods pelo seletor do Deployment;
- usam o maior limite de CPU do primeiro container dos Pods;
- calculam `current_mcpu * multiplier`;
- limitam o resultado entre baseline e `maxCPU`;
- atualizam os containers `znn` e `nginx`;
- usam o subresource `pods/resize`;
- interrompem após erro ao atualizar um Pod;
- não desfazem atualizações já aplicadas em Pods anteriores.

### 8.2 Arredondamento diferente

Python:

```python
round(current_mcpu * multiplier)
```

O `round()` do Python usa empate para o número par.

Java:

```java
Math.round(currentMcpu * multiplier)
```

Para valores positivos terminados em `.5`, `Math.round()` arredonda para cima.

Exemplo:

| Valor intermediário | Python | Java |
|---:|---:|---:|
| `154.5` | `154` | `155` |
| `155.5` | `156` | `156` |

Diferenças de 1m podem se acumular e alterar decisões futuras nas proximidades
dos limites.

### 8.3 Tipos numéricos

No Python, `bool` é uma subclasse de `int`; por isso, `True` pode passar pelo
teste `isinstance(multiplier, (float, int))`.

No Java, o código exige uma primitiva JSON reconhecida como número.

### 8.4 Criação de clientes

As implementações criam clientes e objetos Kubernetes de formas diferentes. O
Java cria um `ApiClient` dentro do modo. O Python possui clientes no
`AdaptContext` e também clientes globais criados durante a importação de
`initial_data`.

Isso muda custo de inicialização mesmo quando o número final de PATCHes é igual.

### 8.5 Equiparação necessária

- [ ] Definir uma regra explícita de arredondamento.
- [ ] Implementar a mesma regra nas duas linguagens.
- [ ] Rejeitar booleanos em ambas.
- [ ] Igualar inicialização e quantidade de clientes quando possível.
- [ ] Comparar payload, endpoint e quantidade de PATCHes.
- [ ] Testar valores exatamente em `.5`, baseline e `maxCPU`.

## 9. Adaptação de tag

Arquivos:

- Python: `autoscalers/csa/scripts/adapt_tag.py`;
- Java: `autoscalers/csa-java/src/main/java/org/csajava/runtime/adapt/tag/AdaptTagRuntime.java`.

### 9.1 Elementos equivalentes

Escada de tags:

```text
100k -> 200k -> 400k -> 600k -> 800k
```

Ambos:

- usam `tag_up` para definir a direção;
- impedem subir além da tag inicial conhecida;
- retornam `skip` nos limites;
- não alteram tag durante rollout;
- podem copiar a CPU atual para a spec do Deployment quando `update_cpu=true`;
- mudam somente um nível por adaptação.

### 9.2 Parser de imagem diferente

Python usa regex e restringe os formatos aceitos.

Java considera a parte após o último `:` como tag, desde que esse `:` esteja
depois da última `/`.

Consequências possíveis:

- imagens aceitas pelo Java podem ser rejeitadas pelo Python;
- referências com digest podem ser interpretadas incorretamente pelo Java;
- casos com formatos incomuns podem divergir.

### 9.3 Operação Kubernetes diferente

Python usa:

```text
PATCH Deployment
```

Java usa:

```text
PUT replaceNamespacedDeployment
```

O PUT Java substitui o recurso completo. O PATCH Python aplica uma alteração
estratégica.

Impacto:

- semântica de concorrência diferente;
- risco diferente de conflito por `resourceVersion`;
- custo HTTP diferente;
- possibilidade diferente de sobrescrever campos alterados por terceiros;
- latência e bytes transmitidos diferentes.

### 9.4 Falha ao listar Pods durante `update_cpu`

No Python, ausência de CPU atual é registrada, mas a alteração de tag ainda pode
prosseguir.

No Java, lista vazia também pode permitir a tag, mas uma exceção da API durante a
listagem é capturada pelo bloco externo e transforma toda a adaptação em erro.

### 9.5 Equiparação necessária

- [ ] Usar o mesmo parser ou uma especificação compartilhada de imagem.
- [ ] Usar o mesmo método Kubernetes em ambas.
- [ ] Preferir PATCH mínimo para reduzir efeitos laterais.
- [ ] Igualar o comportamento quando não há Pods ou a listagem falha.
- [ ] Testar registry com porta, imagem sem tag, digest e tag desconhecida.

## 10. Contexto Kubernetes compartilhado

Arquivos:

- Python: `autoscalers/csa/scripts/adapt_base.py`;
- Java: `autoscalers/csa-java/src/main/java/org/csajava/runtime/adapt/AdaptSupport.java`.

### 10.1 Rollout

No caminho com objetos completos, ambos consideram rollout completo quando:

- `observedGeneration >= generation`;
- `updatedReplicas == spec.replicas`;
- `availableReplicas == spec.replicas`.

Diferença de borda:

- Python pressupõe mais campos presentes e pode lançar exceção;
- Java retorna `false` para rollout quando objetos estruturais estão ausentes.

### 10.2 Seleção de Pods

Ambos constroem seletor a partir de `Deployment.spec.selector.matchLabels`.

Apesar do nome `get_running_pods`/`runningPods`, nenhum filtra explicitamente
`status.phase=Running`. Todos os Pods retornados pelo seletor podem participar.

Isso é uma equivalência entre as versões, mas o nome não corresponde exatamente
ao comportamento.

### 10.3 CPU atual

Ambos:

- leem somente o primeiro container de cada Pod;
- calculam o maior limite entre os Pods;
- não selecionam o container explicitamente pelo nome `znn`.

Essa política está equivalente, mas depende da ordem dos containers.

## 11. Persistência de estado inicial

Arquivos:

- Python: `autoscalers/csa/scripts/initial_data.py`;
- Java: `autoscalers/csa-java/src/main/java/org/csajava/runtime/initialdata/InitialDataStore.java`.

### 11.1 Intenção equivalente

Ambos usam:

- variáveis `CSA_NAME` e `CSA_NAMESPACE`;
- status `initialData` do CustomSelfAdapter;
- annotation `csa.custom-self-adapter.net/initialData` no Pod do CSA;
- campos `tag` e `cpu_limit`;
- regra de não sobrescrever um valor inicial já armazenado.

### 11.2 Merge diferente

Python preserva valores encontrados no `status.initialData` antes de escrever a
annotation.

Java preserva:

1. valores do status;
2. valores já existentes diretamente na annotation do Pod;
3. o novo valor.

O Java é mais resistente ao atraso de reconciliação entre annotation e status.

### 11.3 Cache local Java

O Java mantém um mapa local durante a execução do processo. Após armazenar a tag,
uma leitura imediata pode obter o valor do cache sem depender da reconciliação do
operador.

O Python escreve a annotation e consulta novamente o status. Se o status ainda
não foi atualizado, pode não enxergar o valor recém-armazenado.

### 11.4 Tratamento de falha

Java trata a persistência como best-effort em vários caminhos e ignora algumas
`ApiException`.

Python permite que mais exceções da API sejam propagadas e encerrem o script.

### 11.5 Equiparação necessária

- [ ] Definir uma única fonte de verdade durante o ciclo atual.
- [ ] Usar a mesma estratégia de merge.
- [ ] Igualar cache e consistência imediata.
- [ ] Igualar falhas de leitura e escrita.
- [ ] Testar atraso entre annotation e status.
- [ ] Testar armazenamento de CPU e tag em sequência.

## 12. Erros, stdout e código de saída

### 12.1 Python

O comportamento varia por fase:

- algumas entradas inválidas apenas registram erro e não escrevem stdout;
- alguns erros escrevem `{"result":"error"}`;
- exceções não capturadas encerram o processo com código diferente de zero;
- configuração YAML vazia ou inválida pode causar retorno sem decisão.

### 12.2 Java

O Java tende a retornar JSON estruturado:

```json
{
  "result": "error",
  "message": "..."
}
```

Porém, erros retornados normalmente por um handler não fazem `App` encerrar com
código diferente de zero. O código `1` é usado principalmente para exceções que
chegam até o `catch` do entrypoint.

### 12.3 Impacto

O controlador pode tratar de forma diferente:

- processo sem stdout e exit zero;
- JSON de erro e exit zero;
- JSON de erro e exit um;
- timeout;
- processo encerrado por exceção.

Isso altera retries, logs, contagem de falhas e continuidade dos ciclos.

### 12.4 Equiparação necessária

- [ ] Especificar stdout por classe de erro.
- [ ] Especificar stderr por classe de erro.
- [ ] Especificar o código de saída.
- [ ] Aplicar o contrato em todas as fases.
- [ ] Testar falha de configuração, stdin, API, patch e timeout.

## 13. Logging

Arquivo Python:

- `autoscalers/csa/scripts/adapter_logger.py`.

O Python configura:

- `StreamHandler` para stderr;
- `FileHandler` para `/tmp/adapter.log`;
- várias mensagens informativas por ciclo.

O Java praticamente não registra o caminho normal. Ele escreve principalmente
saída JSON e mensagens de erro de CLI.

### 13.1 Impacto

- Python faz mais I/O;
- Python formata mais strings;
- Python abre e usa arquivo local;
- o volume enviado ao controlador pode diferir;
- Java tem menor observabilidade;
- consumo de CPU e duração dos comandos ficam contaminados pelo logging.

### 13.2 Equiparação necessária

- [ ] Definir o nível de logging do benchmark.
- [ ] Desabilitar o arquivo Python ou implementar custo equivalente no Java.
- [ ] Usar o mesmo destino para logs.
- [ ] Separar benchmark com logging de benchmark sem logging.

## 14. Docker, sistema operacional e dependências

### 14.1 Python

`autoscalers/csa/Dockerfile`:

- base `registry.k8s.lab/custom-self-adapter:python-3-12-latest`;
- instala `kubernetes` e `pyyaml` sem versões;
- copia scripts individualmente.

### 14.2 Java

`autoscalers/csa-java/Dockerfile`:

- build com `gradle:jdk21`;
- runtime `registry.k8s.lab/custom-self-adapter:alpine-latest`;
- instala OpenJDK 21, `jq` e `unzip`;
- bibliotecas Java possuem versões no `build.gradle`;
- não há configuração explícita de heap ou GC.

### 14.3 Diferenças relevantes

- bases finais diferentes;
- potencial diferença entre libc e bibliotecas do sistema;
- tamanhos de imagem diferentes;
- tempo de pull diferente;
- Python não fixa versões das dependências;
- tags `latest` são mutáveis;
- Java fixa bibliotecas da aplicação, mas não fixa todas as imagens por digest;
- ergonomia automática da JVM pode variar conforme versão e limites do
  container.

### 14.4 Equiparação necessária

- [ ] Fixar imagens-base por digest.
- [ ] Fixar versões Python.
- [ ] Gerar lock ou constraints para Python.
- [ ] Registrar versão exata do JRE e do interpretador.
- [ ] Usar a mesma distribuição base quando tecnicamente possível.
- [ ] Pré-puxar imagens antes dos testes ou medir pull separadamente.
- [ ] Documentar flags de JVM.

## 15. Testes automatizados

### 15.1 Resultado atual

Em 28 de agosto de 2026, foi executado:

```bash
cd autoscalers/csa-java
./gradlew test
```

Resultado:

- build bem-sucedido;
- 27 testes;
- zero falhas;
- 13 testes em `ContractParityTest`.

### 15.2 O que `ContractParityTest` realmente prova

O teste:

1. lê uma fixture JSON;
2. lê o `stdout` esperado da fixture;
3. cria um `RuntimeContext` Java;
4. injeta `implicitInput` como hints;
5. executa somente o runtime Java;
6. compara o resultado Java com o JSON esperado.

Ele não:

- executa os scripts Python;
- prova que as fixtures ainda representam o Python atual;
- cria um servidor Kubernetes falso comum;
- compara sequência de chamadas HTTP;
- compara método PATCH contra PUT;
- compara payloads reais;
- compara códigos de saída;
- valida timeouts;
- valida logging;
- valida concorrência ou reconciliação de `initialData`.

### 15.3 Hints desviam do caminho de produção

Nos testes, `RuntimeContext.hints` permite evitar Kubernetes real.

Exemplos de limitações:

- adaptação de réplicas apenas lê `deployment_patch_success`;
- adaptação de CPU não prova que cada Pod recebeu o payload correto;
- adaptação de tag não testa o PUT real;
- `InitialDataStore` usa modo local quando há hints.

### 15.4 Cobertura Python

Não existe diretório de testes automatizados para `autoscalers/csa` no estado
atual do repositório.

### 15.5 Equiparação necessária

- [ ] Criar suíte Python.
- [ ] Executar Python e Java a partir da mesma fixture.
- [ ] Comparar stdout, stderr e código de saída.
- [ ] Usar o mesmo servidor HTTP Kubernetes falso.
- [ ] Comparar método, endpoint e corpo de cada chamada.
- [ ] Testar todas as fronteiras de taxa.
- [ ] Testar arredondamentos `.5`.
- [ ] Testar ausência de Pod, rollout, API indisponível e conflito.
- [ ] Testar persistência com status atrasado.
- [ ] Executar testes in-cluster antes da rodada massiva.

## 16. Pipeline experimental

Arquivos:

- `run_tests.sh`;
- `run_tests_csa.sh`.

### 16.1 Elementos equivalentes

- mesma aplicação `kube-znn`;
- mesmo overlay inicial `800k`;
- mesmo cenário Locust;
- mesmo host;
- mesmos quatro processos Locust;
- mesmos manifests de aplicação por par de perfil.

### 16.2 Ordem fixa

Python é executado antes de Java nos grupos CSA.

Isso permite efeitos de:

- temperatura e frequência da CPU;
- cache;
- estado residual do cluster;
- pressão de memória;
- processos e métricas atrasadas;
- evolução temporal da infraestrutura.

### 16.3 Reset diferente em HQ

Nos cenários Python HQ, o CSA é removido após cada execução.

Em `run_tests.sh`, o Java HQ 25% permanece ativo até o cenário HQ 50%. A
aplicação é recriada, mas o mesmo CustomSelfAdapter pode preservar:

- annotation;
- `status.initialData`;
- Pod do CSA;
- estado interno do controlador;
- ciclos ocorridos durante a recriação da aplicação.

Em `run_tests_csa.sh`, HQ 25% e HQ 50% Java podem compartilhar também o estado
adaptado da aplicação, pois o segundo cenário apenas altera a estratégia de
rolling update e executa nova carga.

### 16.4 Cleanup assíncrono

Nem todos os `kubectl delete` são seguidos de espera pela remoção completa do
CSA e de seus Pods.

No Java H, há um `sleep 60` antes da remoção do CSA, enquanto o Python remove o
CSA antes do intervalo. Depois, o Java pode avançar sem esperar a finalização da
remoção.

### 16.5 Readiness

O pipeline usa principalmente `sleep 5` depois de aplicar o CSA. Ele não aguarda
explicitamente:

- Pod do CSA criado;
- imagem baixada;
- Pod Ready;
- primeiro ciclo de métrica concluído;
- aplicação completamente Ready em todos os cenários.

Isso pode favorecer a imagem menor ou o runtime que inicia mais rápido. Também
pode gerar carga antes de o autoscaler estar operacional.

### 16.6 Falhas do shell

Os scripts não usam `set -euo pipefail`. Vários comandos não possuem `|| return
1`. Uma falha de `kubectl` pode permitir que o cenário continue em estado
inválido.

### 16.7 Evidência dos resultados intermediários

O `README.md:120-127` registra:

- timeouts frequentes do Java com timeout de 2000ms;
- redução, mas não eliminação, dos problemas com 5000ms;
- erros repetidos em `adapt_replicas` no Java H;
- ausência de adaptação efetiva de CPU/tag no Java VQ;
- adaptação efetiva de réplicas no Python H.

Isso comprova que os controladores não apresentaram o mesmo comportamento
funcional durante essas execuções.

### 16.8 Equiparação necessária

- [ ] Recriar aplicação e CSA antes de todo cenário.
- [ ] Apagar e verificar `initialData` antes de cada execução.
- [ ] Esperar remoção completa dos recursos.
- [ ] Esperar readiness da aplicação e do CSA.
- [ ] Verificar que o primeiro ciclo concluiu antes da carga.
- [ ] Usar `set -euo pipefail` ou tratamento explícito equivalente.
- [ ] Interromper a iteração quando setup ou cleanup falhar.
- [ ] Alternar a ordem Python/Java ou randomizá-la de forma registrada.
- [ ] Pré-puxar ambas as imagens.
- [ ] Salvar logs do controlador e do CSA junto ao resultado.
- [ ] Salvar configuração e digest efetivos junto ao CSV.

## 17. Diferenças que podem ser atribuídas à linguagem

Depois que configuração e comportamento estiverem equiparados, podem permanecer
como diferenças legítimas de linguagem/runtime:

- cold start de CPython contra JVM;
- custo de parsing e serialização JSON;
- custo das bibliotecas Kubernetes de cada ecossistema;
- consumo de CPU e memória do runtime;
- garbage collection;
- tamanho do processo;
- tempo total de cada comando shell;
- desempenho sob o mesmo limite de CPU e memória.

Mesmo nesses casos, as duas versões devem executar a mesma quantidade de
operações externas. Uma chamada HTTP adicional não é uma propriedade necessária
da linguagem; é uma diferença de implementação.

## 18. Matriz mínima de testes de equivalência

### 18.1 Métrica

- [ ] payload válido;
- [ ] sem métricas;
- [ ] métrica vazia;
- [ ] réplicas ausentes;
- [ ] target ausente;
- [ ] valor atual ausente;
- [ ] tipos inválidos.

### 18.2 Avaliação

- [ ] `rate` imediatamente abaixo de `0.90`;
- [ ] `rate == 0.90`;
- [ ] `rate` imediatamente abaixo de `0.95`;
- [ ] `rate == 0.95`;
- [ ] `rate > 1`;
- [ ] mínimo e máximo de réplicas;
- [ ] baseline e máximo de CPU;
- [ ] primeira avaliação sem `initialData`;
- [ ] configuração sem estratégias;
- [ ] lista vazia;
- [ ] cada combinação `h`, `hq`, `v`, `vq`;
- [ ] Deployment ausente;
- [ ] Pods ausentes;
- [ ] falha de API.

### 18.3 Réplicas

- [ ] parâmetro válido;
- [ ] parâmetro ausente;
- [ ] string, decimal e booleano;
- [ ] sucesso de PATCH;
- [ ] erro de PATCH;
- [ ] mesma sequência HTTP.

### 18.4 CPU

- [ ] multiplicador de alta e baixa carga;
- [ ] resultado terminado em `.5`;
- [ ] clamp no baseline;
- [ ] clamp no máximo;
- [ ] rollout ativo;
- [ ] nenhum Pod;
- [ ] vários Pods com CPUs diferentes;
- [ ] falha no primeiro e no último Pod;
- [ ] payload com `znn` e `nginx` idêntico.

### 18.5 Tag

- [ ] todas as transições adjacentes;
- [ ] limites `100k` e `800k`;
- [ ] restauração até a tag inicial;
- [ ] registry com porta;
- [ ] imagem sem tag;
- [ ] digest;
- [ ] tag desconhecida;
- [ ] `update_cpu=true` e `false`;
- [ ] rollout ativo;
- [ ] falha ao listar Pods;
- [ ] falha ao atualizar Deployment;
- [ ] mesmo método e payload Kubernetes.

### 18.6 Estado inicial

- [ ] status vazio;
- [ ] annotation vazia;
- [ ] somente CPU armazenada;
- [ ] somente tag armazenada;
- [ ] ambos armazenados;
- [ ] annotation mais nova que status;
- [ ] falha de leitura do CR;
- [ ] falha de patch do Pod;
- [ ] ausência de `CSA_NAME` ou `CSA_NAMESPACE`.

## 19. Critérios de aceite antes de uma nova rodada

Uma nova rodada destinada a comparar linguagens deve começar somente quando:

1. A configuração efetiva for semanticamente idêntica por par.
2. As decisões para a matriz de entradas forem idênticas.
3. O arredondamento for idêntico.
4. A sequência de chamadas Kubernetes for idêntica.
5. Métodos HTTP e payloads forem semanticamente idênticos.
6. Stdout, erros e códigos de saída seguirem o mesmo contrato.
7. Persistência e recuperação de `initialData` forem equivalentes.
8. O nível de logging for igual.
9. Imagens e dependências estiverem fixadas.
10. O pipeline fizer reset e readiness simétricos.
11. Testes diferenciais locais e in-cluster passarem.
12. Cada resultado registrar Git SHA, digest, configuração e ordem de execução.

## 20. Ordem recomendada para a equiparação

### Etapa 1: tornar o experimento reproduzível

- corrigir os scripts de build;
- explicitar os quatro perfis;
- fixar dependências e imagens;
- registrar digests e configuração.

### Etapa 2: equiparar decisões puras

- baseline inicial;
- estratégias vazias;
- parser de quantidades;
- precisão da taxa;
- arredondamento de CPU.

### Etapa 3: equiparar efeitos Kubernetes

- chamadas de réplicas;
- PATCH da tag;
- payloads de CPU;
- persistência de `initialData`;
- tratamento de falhas.

### Etapa 4: criar prova automatizada de paridade

- executar Python e Java reais;
- compartilhar o mesmo servidor Kubernetes falso;
- comparar decisão e trace HTTP;
- adicionar testes in-cluster focados.

### Etapa 5: corrigir o pipeline

- reset completo;
- readiness;
- ordem alternada;
- falha rápida;
- coleta de metadados.

### Etapa 6: invalidar e repetir resultados contaminados

Resultados obtidos antes da equiparação não devem fundamentar uma conclusão de
causalidade exclusiva da linguagem. Eles podem permanecer como resultados
exploratórios da migração.

## Parecer para uso científico

Formulação adequada para o estado atual:

> As versões Python e Java implementam o mesmo objetivo e compartilham o núcleo
> da política de adaptação, mas ainda apresentam diferenças de configuração,
> interação com Kubernetes, persistência de estado, arredondamento, logging e
> execução experimental. Portanto, as diferenças observadas não podem ser
> atribuídas exclusivamente à linguagem.

Formulação aceitável após cumprir os critérios de equiparação:

> As implementações foram submetidas aos mesmos contratos de decisão, chamadas
> Kubernetes, configuração, recursos, logging e ciclo experimental. As
> diferenças restantes correspondem ao runtime e ao ecossistema de cada
> linguagem dentro do contrato shell avaliado.
