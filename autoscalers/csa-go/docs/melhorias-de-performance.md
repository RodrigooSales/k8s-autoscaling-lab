# Possíveis melhorias de performance — Python, Java e Go

Data: 2026-09-23. Baseline e limites em [levantamento](levantamento.md).
Comandos e contexto em [registro da investigação](comandos-investigacao.md).

## Classificação e regra de comparação

Todas as oportunidades abaixo estão **somente documentadas**. O trabalho repetido é
observável no código; o ganho de performance é uma hipótese, sem tempos, percentuais ou
ranking medidos. A ordem dos itens não representa prioridade comprovada.

Cada proposta identifica aplicabilidade, risco de paridade e verificação futura.
Mudanças que reduzam chamadas, alterem estado observado, formato/volume de logs ou ciclo
de vida do processo não podem ser aplicadas somente ao Go no experimento de referência.
Uma otimização específica de runtime pode interessar apenas a uma linguagem, mas precisa
ser identificada como variante otimizada. Nenhuma modificação Python/Java será feita nesta branch.
A comparação empírica ocorrerá depois das cargas executadas pelo usuário.

## P01 — inicialização de um processo por etapa

**Evidência:** [executor shell](../../../../custom-self-adapter/internal/execute/shell/shell.go#L55)
cria e inicia um comando por chamada. As configurações Python invocam scripts; Java invoca
o launcher da distribuição. O Go seguirá o mesmo ciclo. Imports, classes, cliente e estado
em memória não sobrevivem entre esses processos.

**Hipótese/proposta:** um processo persistente por linguagem poderia amortizar inicialização,
carregamento e conexões. Aplicável às três linguagens; exige também alteração de arquitetura
do runtime e do protocolo de execução, não apenas troca de compilador.

**Paridade:** alto risco. Muda ciclo de vida, aquecimento da JVM, cache, tratamento de falhas
e isolamento. Não comparar um Go persistente com Python/Java iniciados a cada etapa.

**Verificação futura:** comparar decisões, reinício após erro, isolamento entre ciclos e
estado observado. Medir latência das etapas e comportamento durante as mesmas cargas,
em uma variante com a mesma arquitetura para todas as linguagens. Não atribuir ao Go o
ganho de uma mudança de arquitetura.

## P02 — duas listagens de Pods na adaptação de CPU

**Evidência:** [adapt_cpu.py:57](../../csa/scripts/adapt_cpu.py#L57) obtém a lista usada no resize;
[adapt_base.py:119](../../csa/scripts/adapt_base.py#L119) lista novamente para obter a CPU atual.
Java repete esse fluxo em [AdaptCpuRuntime.java:176](../../csa-java/src/main/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntime.java#L176).
O teste [AdaptCpuRuntimeTest.java:43](../../csa-java/src/test/java/org/csajava/runtime/adapt/cpu/AdaptCpuRuntimeTest.java#L43) exige duas listagens.

**Hipótese/proposta:** calcular a CPU a partir da primeira lista poderia eliminar um GET
por adaptação bem-sucedida nesse caminho. Aplicável a Python, Java e futuro Go.

**Paridade:** alto risco. Duas respostas podem divergir durante rollout/resize;
reutilizar a primeira altera o instante do estado usado no cálculo. Também muda o ponto
de ocorrência de falhas e o trabalho imposto ao API server.

**Verificação futura:** cenários em que as duas respostas têm CPUs/Pods diferentes,
falha na segunda leitura e contagem de chamadas; depois comparar resultados sob carga.
Preservar as duas consultas no baseline.

## P03 — releituras e persistência indireta de initialData

**Evidência:** [initial_data.py:75](../../csa/scripts/initial_data.py#L75) lê o status a cada consulta.
Gravar um campo ausente passa por leitura do status, leitura do Pod, nova leitura do status
e patch do Pod. Java segue o fluxo em
[InitialDataStore.java:94](../../csa-java/src/main/java/org/csajava/runtime/initialdata/InitialDataStore.java#L94).
O operador transfere a anotação para status de forma assíncrona
([statusSync](../../../../custom-self-adapter-operator/internal/reconcile/reconcile.go#L328)).
Os testes existentes explicitam releitura e perda de um primeiro campo quando duas escritas
ocorrem antes da reconciliação ([InitialDataStoreTest.java:100](../../csa-java/src/test/java/org/csajava/runtime/initialdata/InitialDataStoreTest.java#L100)).

**Hipótese/proposta:** reutilizar uma leitura dentro da operação, agrupar persistências
ou redesenhar o armazenamento pode reduzir GETs/PATCHes e parsing. Aplicável às três
linguagens; um redesenho pode envolver também o operador.

**Paridade:** alto risco. Cache de leitura ou de escrita altera atrasos observáveis,
merge, falhas e proteção do valor inicial. Gravar diretamente no status altera ainda
o contrato e permissões. Não é uma substituição transparente.

**Verificação futura:** estado ausente, valor já armazenado, reconciliação atrasada,
duas escritas sequenciais, múltiplos escritores e falha em cada chamada. Medir chamadas e
resultado das adaptações sob carga depois de definir a política comum de consistência.

## P04 — consultas de CPU antes de decidir se serão usadas

**Evidência:** [evaluate.py:45](../../csa/scripts/evaluate.py#L45) lê CPU atual e inicial antes de selecionar qualquer
estratégia, mesmo quando somente réplicas estão habilitadas ou a razão está na banda sem
adaptação. Java resolve o mesmo estado antes das decisões em
[EvaluateRuntime.java:112](../../csa-java/src/main/java/org/csajava/runtime/evaluate/EvaluateRuntime.java#L112).

**Hipótese/proposta:** consultar estado sob demanda poderia reduzir chamadas nos perfis
horizontais e em avaliações sem adaptação. Aplicável às três linguagens.

**Paridade:** alto risco. Hoje CPU ausente pode impedir uma adaptação horizontal, e a
avaliação pode persistir a CPU inicial mesmo sem escolher uma estratégia. Alterar a ordem
também altera falhas e logs.

**Verificação futura:** matriz de estratégias, bandas de carga, CPU ausente e estado
inicial ausente; conferir decisões, efeitos colaterais e ordem das chamadas. Só depois
medir a variante equivalente nas três implementações.

## P05 — patches contendo o objeto carregado

**Evidência:** [adapt_replicas.py:13](../../csa/scripts/adapt_replicas.py#L13), [adapt_tag.py:122](../../csa/scripts/adapt_tag.py#L122) e
[initial_data.py:63](../../csa/scripts/initial_data.py#L63) enviam respectivamente o Deployment ou Pod carregado, com a
alteração desejada. Java serializa esses objetos completos nos caminhos equivalentes.

**Hipótese/proposta:** patches menores podem reduzir serialização e bytes enviados.
Aplicável às três linguagens. O resize de CPU já usa corpo restrito aos containers e CPU;
não confundir esse caminho com o patch de Deployment.

**Paridade:** alto risco. Omissão de campos, null, listas e resourceVersion podem mudar
semântica de merge/conflito e interação com alterações concorrentes. Uma chamada com menos
bytes não é automaticamente equivalente.

**Verificação futura:** comparar efeitos no API server para metadados, campos adicionais,
concorrência, falhas e strategic merge. Medir tamanho dos corpos e efeitos sob a mesma carga.
Preservar os corpos de referência nesta branch.

## P06 — escrita síncrona e abertura dos destinos de log

**Evidência:** [adapter_logger.py:3](../../csa/scripts/adapter_logger.py#L3) constrói FileHandler e StreamHandler a cada
instanciação, antes de basicConfig; isso não significa que cada instanciação instale novos
handlers, pois a configuração do logger raiz pode já existir.
[AdapterLogger.java:30](../../csa-java/src/main/java/org/csajava/logging/AdapterLogger.java#L30) abre um BufferedWriter por instância, e
[AdapterLogger.java:57](../../csa-java/src/main/java/org/csajava/logging/AdapterLogger.java#L57) executa flush por mensagem e escreve em stderr.
Não há fechamento explícito do writer nessa classe; os processos são curtos.

**Hipótese/proposta:** compartilhar destinos por processo mantendo flush e eventos pode
reduzir aberturas. Agrupar escritas, reduzir verbosidade ou usar logging assíncrono são
propostas separadas e mais invasivas. Aplicável a Python, Java e ao desenho futuro Go.

**Paridade:** compartilhar destinos exige preservar ordenação e erros de I/O. Bufferizar
ou remover mensagens muda trabalho, visibilidade e possível perda de logs em timeout.
Flush de buffer não deve ser confundido com fsync. Não foi demonstrado vazamento cumulativo
entre processos.

**Verificação futura:** comparar eventos, formatação, destinos, ordenação, falha de abertura
e encerramento forçado. Medir chamadas de I/O e resultados das cargas em variantes com
a mesma política de logging; não desabilitar logs apenas no Go.

## P07 — inicialização e imports com efeitos colaterais no Python

**Evidência:** [evaluate.py:5](../../csa/scripts/evaluate.py#L5) importa módulos de adaptação para acessar constantes.
Esses módulos importam initial_data, que configura autenticação, logger e clientes durante
o import ([initial_data.py:13](../../csa/scripts/initial_data.py#L13)). AdaptContext configura novamente o acesso
([adapt_base.py:58](../../csa/scripts/adapt_base.py#L58)). get_csa_status constrói CustomObjectsApi a cada leitura.
O objeto appsV1 de initial_data é criado, mas não utilizado no próprio módulo.

**Hipótese/proposta:** separar constantes sem efeitos colaterais, remover a instanciação
sem uso e reutilizar a configuração/cliente dentro do processo podem reduzir inicialização
Python. Java já passa um ApiClient comum aos wrappers de API de cada etapa; não atribuir
automaticamente a ele a mesma duplicação. Go deve evitar introduzir custo extra acidental,
preservando os acessos e falhas exigidos pelo baseline.

**Paridade:** mesmo retirar inicialização muda o momento de falhas de autenticação/import.
Instanciar um cliente não prova que ocorreu uma requisição ou conexão; esse custo não foi
medido. Comparações de SDK devem registrar quais mudanças pertencem à variante otimizada.

**Verificação futura:** conferir importações, autenticação, falhas e sequência HTTP,
depois medir a variante Python com as mesmas entradas e imagens registradas.
Não mover inicialização nem constantes nas referências nesta branch.

## P08 — parsing duplicado e serialização repetida no Python

**Evidência:** AdaptContext já faz json.loads do stdin
([adapt_base.py:40](../../csa/scripts/adapt_base.py#L40)), e evaluate faz novamente
([evaluate.py:28](../../csa/scripts/evaluate.py#L28)). A métrica embutida em metrics[0].value exige outro parsing por
contrato; esse segundo formato não é a mesma duplicação. write_evaluation serializa a
mesma avaliação para log e stdout ([evaluate.py:93](../../csa/scripts/evaluate.py#L93)).

**Hipótese/proposta:** reutilizar ctx.spec e uma string de avaliação pode reduzir parsing
e alocações Python. Java já retém stdinJson, mas também serializa a avaliação para log
e depois para stdout. A reutilização de saída pode ser estudada no Java e no futuro Go.

**Paridade:** preservar tipos numéricos, espaçamento, Unicode, ausência de newline,
mensagem de log e comportamento em erro. Não transformar o campo value de string JSON
em objeto, pois isso altera o contrato com o runtime.

**Verificação futura:** igualdade dos resultados e logs para payloads pequenos/grandes,
Unicode e valores numéricos. Medir somente em comparação posterior; o ganho pode ser
irrelevante frente à latência das APIs.

## P09 — conversões intermediárias na serialização Java

**Evidência:** [KubernetesJson.java:15](../../csa-java/src/main/java/org/csajava/kubernetes/KubernetesJson.java#L15) faz modelo → string JSON →
árvore Gson → remoção recursiva de vazios → string. Em réplicas há mais um parse e
toString ([AdaptReplicasRuntime.java:87](../../csa-java/src/main/java/org/csajava/runtime/adapt/replicas/AdaptReplicasRuntime.java#L87)).
JsonOut também implementa um escritor próprio para compatibilidade com o JSON Python
([JsonOut.java:17](../../csa-java/src/main/java/org/csajava/io/JsonOut.java#L17)).

**Hipótese/proposta:** reduzir representações intermediárias pode diminuir CPU e alocações
Java. A mesma preocupação vale ao futuro Go; não foi identificado esse mesmo percurso
explícito no código Python, cujo custo interno depende do SDK.

**Paridade:** a remoção de defaults e o escritor não são descartáveis sem prova:
mantêm escolhas de serialização da referência. Preservar null versus ausência, coleções,
campos Kubernetes, ordem relevante e escapes.

**Verificação futura:** comparar corpos e stdout contra a referência antes de qualquer
medição. Registrar uso de CPU/memória e resultados das cargas somente quando a variante
Java estiver aprovada em branch própria.

## P10 — startup e configuração da JVM

**Evidência:** [Dockerfile Java](../../csa-java/Dockerfile#L20) já configura
`-XX:ActiveProcessorCount=1 -XX:TieredStopAtLevel=1`.
[build.gradle](../../csa-java/build.gradle#L14) já exclui runtimes de autenticação,
protobuf, Prometheus e outros transitivos.
[BuildProfileTest.java:23](../../csa-java/src/test/java/org/csajava/config/BuildProfileTest.java#L23) registra essas escolhas como expectativas.

**Hipótese/proposta:** outras configurações de startup/class loading ou empacotamento
podem beneficiar processos Java curtos. O efeito de novas flags ou de CDS/AOT não foi
investigado nem medido; pode haver regressão. As otimizações já presentes não são trabalho
novo a reivindicar.

**Aplicabilidade/paridade:** específico de Java; Python/Go exigiriam estudos próprios de
runtime, não flags equivalentes inventadas. Preservar decisões, recursos do container e
modelo de processo. Distinguir comparação das versões atuais de comparação de versões
otimizadas por linguagem.

**Verificação futura:** conferir dependências realmente usadas, resultados e startup
no ambiente do experimento; registrar JDK, flags e digest, e comparar sob cargas feitas
pelo usuário. Não modificar o classpath ou Dockerfile Java nesta branch.

## P11 — adaptações sequenciais e patches sem mudança efetiva

**Evidência:** [adapt_cpu.py:73](../../csa/scripts/adapt_cpu.py#L73) limita o valor calculado e ainda percorre os Pods
para resize ([adapt_cpu.py:94](../../csa/scripts/adapt_cpu.py#L94)). Réplicas pode selecionar o valor atual por ceil,
como razão 0.95 com poucas réplicas, e ainda executar patch. Java mantém os caminhos.
O teste de CPU exige parada na primeira falha.

**Hipótese/proposta:** evitar patches sem mudança ou paralelizar resize pode reduzir
trabalho/tempo em alguns casos. Aplicável às três linguagens; são duas propostas diferentes.

**Paridade:** alto risco. Supressão de patch muda número de chamadas, erros e efeitos
do API server. Concorrência muda ordem, estados parciais, pressão sobre a API e parada
na primeira falha. Não equivale a simplesmente usar goroutines no Go.

**Verificação futura:** casos de arredondamento/limites sem mudança, falha no primeiro,
intermediário e último Pod e efeitos já aplicados antes da falha. Definir semântica comum
antes de medir a variante otimizada.

## P12 — caminho de timeout no runtime compartilhado

**Evidência:** [executor shell, linhas 76–86](../../../../custom-self-adapter/internal/execute/shell/shell.go#L76)
usa um canal não bufferizado. A goroutine envia o retorno de cmd.Wait(); no caminho de
timeout a função retorna sem receber desse canal. Pela leitura, existe um caminho em
que a goroutine fica bloqueada no envio após o encerramento do filho.

**Hipótese/proposta:** corrigir o ciclo de espera/cancelamento pode evitar retenção de
goroutines após timeouts. É uma oportunidade no runtime comum, que afeta estratégias
Python, Java e Go; não é uma otimização da linguagem da estratégia.

**Paridade:** requer mudança e teste no repositório do runtime, fora desta entrega/branch.
A frequência de timeout pode ser diferente por implementação e introduzir custo desigual.
A retenção não foi reproduzida nem medida nesta investigação.

**Verificação futura:** teste de processo com timeout no runtime real, término do filho,
liberação da goroutine, logs e código de saída; depois usar a mesma versão corrigida do
runtime em todas as imagens da comparação.

## Pontos revisados que não justificam otimização imediata

- YAML é carregado nas etapas que precisam dele. Cache entre etapas não sobreviveria ao
  modelo de processos; não foi demonstrada releitura redundante na mesma etapa normal.
  Fontes: [adapt_base.py:133](../../csa/scripts/adapt_base.py#L133) e [ConfigLoader.java:15](../../csa-java/src/main/java/org/csajava/config/ConfigLoader.java#L15).
- O Python chama re.match com a expressão de imagem e volta a interpretar a imagem para
  substituir a tag. Não foi medido o custo nem inspecionado o cache de regex do runtime;
  não afirmar que recompila a expressão a cada chamada.
- Comparação de quantidades inclui truncamento, limites e arredondamento próprios.
  Trocar pelo parser nativo de outro SDK pode alterar resultados; ver [paridade](paridade.md).
- Reduzir timeout não reduz a duração do trabalho; pode apenas interrompê-lo. A política
  aprovada e a diferença entre CSVs de aplicação e custo da estratégia estão em
  [integração e timeouts](integracao-e-timeouts.md).

## Situação

Nenhuma proposta aplicada. Nenhum ganho comprovado. Nenhuma carga executada.
A investigação inicial está concluída; a comprovação funcional comparativa e as medições
dependem das próximas entregas autorizadas e dos experimentos do usuário.
