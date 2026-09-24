# Paridade — observações das entregas 1 a 3

Data/revisão: [levantamento](levantamento.md). Método: leitura estática do código.
Python permanece como referência; as diferenças abaixo não autorizam editar as versões
existentes. Os contratos JSON da entrega 2 registram somente os resultados esperados pelo
Python e serão consumidos pelos testes Go.

## Fluxo que o baseline precisa preservar

| Área | Observação |
|---|---|
| Métrica | Usa apenas a primeira entrada de kubernetesMetrics e preserva no Python os tipos dos valores recebidos. |
| Avaliação | Razão = int(parse_quantity(atual)) / (int(parse_quantity(alvo)) × 1000). Réplicas vêm de resource.spec.replicas, não do campo current_replicas da métrica. |
| Prioridade | Réplicas, CPU e tag em ambas as direções. Banda 0.90–0.95 sem seleção; 0.95 pertence ao ramo de alta carga. |
| Réplicas | Adaptação aceita o parâmetro inteiro; os limites são aplicados na avaliação, não novamente no adaptador. Python aceita bool como int. |
| CPU atual | Máximo da CPU do primeiro container de cada Pod retornado por labels. O nome get_running_pods não implica filtro por phase=Running. |
| Resize | Duas listagens no caminho normal, limites calculados, patches sequenciais em znn/nginx e parada na primeira falha. |
| Limites CPU | Aplicar máximo e depois mínimo inicial, preservando essa ordem inclusive se a configuração for inconsistente. |
| Tag | Escada 100k/200k/400k/600k/800k; bloqueio da subida ao encontrar igualdade com a tag inicial, não um clamp genérico por índice. |
| update_cpu | O patch de tag pode atualizar CPU de todos os containers do template; não apenas znn/nginx como no resize. |
| Estado | Leitura do status e escrita da anotação no Pod. Preservar a janela de reconciliação e as releituras. |
| Sem seleção | A avaliação emite stdout vazio. Não substituir automaticamente por {}, null ou uma estratégia nova. |

Fontes: [metric_nginx_req_duration.py:13](../../csa/scripts/metric_nginx_req_duration.py#L13), [evaluate.py:28](../../csa/scripts/evaluate.py#L28),
[adapt_base.py:96](../../csa/scripts/adapt_base.py#L96), [adapt_cpu.py:57](../../csa/scripts/adapt_cpu.py#L57),
[adapt_tag.py:90](../../csa/scripts/adapt_tag.py#L90), [adapt_replicas.py:34](../../csa/scripts/adapt_replicas.py#L34).

## Diferenças e casos delicados identificados

### D01 — conversão de CPU fracionária

Python usa round na conversão de cores para millicores; Java usa Math.round em
CpuQuantity. Para valores de meia unidade par, como o cálculo 2.5 millicores, as regras
diferem. É uma diferença deduzida do código dos helpers; o SDK pode normalizar a quantidade
antes de chamar o helper em produção. Confirmar essa trajetória antes de generalizar.

Fontes: [adapt_base.py:146](../../csa/scripts/adapt_base.py#L146) e [CpuQuantity.java:7](../../csa-java/src/main/java/org/csajava/util/CpuQuantity.java#L7).
Não confundir com o cálculo de resize: Java usa Math.rint, e há teste de arredondamento
para par em [AdaptCpuRuntimeTest.java:30](../../csa-java/src/test/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntimeTest.java#L30).
O helper Java de nanos usa Integer.parseInt, que também limita a faixa em comparação ao int Python.

### D02 — tipos retornados pela métrica

Python copia target_value/current_value sem converter o tipo. Java obtém strings por
JsonUtil.stringPath e emite essas strings. Entradas numéricas podem produzir JSON com
tipos diferentes. Não declarar paridade para todas as entradas só porque quantidades
string usuais coincidem.

O fixture metric_numeric_value_types registra a saída numérica esperada pelo Python para
ser usada no teste Go. Ele não contém uma saída Java alternativa.

Fontes: [metric_nginx_req_duration.py:13](../../csa/scripts/metric_nginx_req_duration.py#L13),
[MetricRuntime.java:30](../../csa-java/src/main/java/org/csajava/runtime/metric/MetricRuntime.java#L30) e [JsonUtil.java:70](../../csa-java/src/main/java/org/csajava/util/JsonUtil.java#L70).

Na entrega 3, Go manteve os valores como `json.RawMessage` e serializa os tipos JSON
recebidos sem coerção. Os dois fixtures de métrica verificam a primeira entrada e os tipos
numéricos/textuais definidos pelo contrato Python.

### D03 — ausência ou erro de configuração

Python abre /config.yaml diretamente; YAML vazio/inválido pode retornar None, e erro de
abertura propaga. Java tenta também config.yaml local e pode devolver mapa vazio,
levando aos defaults. As configurações válidas versionadas coincidem nos parâmetros
comportamentais, mas isso não prova equivalência em falhas.

Fontes: [adapt_base.py:133](../../csa/scripts/adapt_base.py#L133), [ConfigLoader.java:15](../../csa-java/src/main/java/org/csajava/config/ConfigLoader.java#L15).

### D04 — Pod do CSA sem annotations

No Python, se annotations é None, set_self_annotation cria um dicionário local, mas não
o atribui de volta a self_pod.metadata.annotations antes do patch. Java faz essa atribuição.
A leitura indica comportamento diferente nesse caso; o comportamento efetivo da serialização
e do API server ainda não foi exercitado.

Fontes: [initial_data.py:63](../../csa/scripts/initial_data.py#L63),
[InitialDataStore.java:149](../../csa-java/src/main/java/org/csajava/runtime/initialdata/InitialDataStore.java#L149).

### D05 — primeiro ciclo e atraso de reconciliação

Em evaluate Python, initial_cpu_data continua zero após store_cpu_limit. Java explicita
initial_mcpu=0 quando não há valor armazenado. Corrigir esse valor no Go alteraria a
primeira decisão. Além disso, duas escritas antes da reconciliação podem sobrescrever
campos da anotação em vez de acumular estado.

Fontes: [evaluate.py:50](../../csa/scripts/evaluate.py#L50),
[EvaluateRuntime.java:330](../../csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java#L330),
[InitialDataStoreTest.java:123](../../csa-java/src/test/java/org/csajava/runtime/initialdata/InitialDataStoreTest.java#L123).
O teste Java é evidência da intenção do contrato existente, não um teste executado nesta entrega.

### D06 — truthiness, ausência de CPU e entradas inválidas

Python testa presença/truthiness de tag_up e update_cpu; Java usa getAsBoolean.
Python e Java também têm diferenças de validação e tratamento de valores ausentes/nulos.
Na tag, o Python exige current_mcpu truthy para atualizar os limites; Java testa non-null
no ramo de atualização. São pontos de caracterização, não equivalência garantida.

Fontes: [adapt_tag.py:58](../../csa/scripts/adapt_tag.py#L58), [adapt_tag.py:110](../../csa/scripts/adapt_tag.py#L110),
[AdaptTagRuntime.java:154](../../csa-java/src/main/java/org/csajava/runtime/adapt/tag/AdaptTagRuntime.java#L154),
[AdaptTagRuntime.java:198](../../csa-java/src/main/java/org/csajava/runtime/adapt/tag/AdaptTagRuntime.java#L198), [JsonUtil.java:106](../../csa-java/src/main/java/org/csajava/util/JsonUtil.java#L106).

## Consequência para a comparação

Decisões e patches compatíveis nos casos usuais não equivalem a paridade exaustiva.
As diferenças observadas permanecem documentadas acima. Os contratos não guardam resultados
esperados específicos do Java e não afirmam equivalência onde o comportamento diverge.
Não corrigir diferenças nem otimizações nas referências nesta branch. As propostas de reduzir trabalho estão separadas em
[melhorias de performance](melhorias-de-performance.md).

## D07 — avaliação e persistência initialData Go

O fluxo implementado na entrega 4 mantém esta ordem por processo: validar o JSON e a
identidade do recurso, carregar configuração in-cluster, ler Deployment, abrir
`/config.yaml`, converter a métrica, listar Pods pelo `spec.selector.matchLabels`, ler
`status.initialData` e só então escolher a estratégia. A consulta de Pods permanece ativa
nos perfis somente horizontais. A lista não filtra `Pod.status.phase`; para cada Pod usa
somente o primeiro container e considera o máximo de CPU. Sem Pods a avaliação termina
sem stdout; Pods sem qualquer `resources` no primeiro container reproduzem o erro do
`max()` Python vazio.

`client-go` converte os objetos do Deployment e dos Pods a partir de respostas unstructured.
O snapshot retém separadamente se o campo `resources` estava ausente ou era um objeto vazio,
pois os tipos Go gerados representam ambos como struct zero. Assim, `get_spec_mcpu` ignora
containers sem o campo e retorna zero se o primeiro objeto `resources` não trouxer CPU.
Essa preservação usa a mesma chamada API e não faz consultas adicionais.

O acesso ao estado usa GET no subrecurso `/apis/custom-self-adapter.net/v1/namespaces/{CSA_NAMESPACE}/customselfadapters/{CSA_NAME}/status`.
Se não houver `cpu_limit` truthy, Go reproduz as releituras: status para obter o valor,
status durante a tentativa de escrita, leitura do Pod do próprio CSA, outro GET de status e
patch estratégico do objeto Pod. O avaliador mantém baseline local zero nesse ciclo até a
reconciliação do operador; não há espera ou polling local. Se `metadata.annotations` vier
nulo, Go também deixa o mapa fora do Pod serializado, preservando a particularidade Python
registrada em D04.

O resultado selecionado mantém a ordem externa Python `strategy`, `parameters`, a forma dos
valores e stdout sem newline. Nenhuma adaptação de réplicas, CPU ou tag foi implementada
nesta entrega; somente a avaliação escolhe e emite a estratégia.
