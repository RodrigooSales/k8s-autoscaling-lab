# Contratos e testes unitários Go

Data/revisão e comandos executados: [levantamento](levantamento.md) e
[registro de comandos](comandos-investigacao.md). Este documento descreve os contratos
compartilhados consumidos pela suíte Go e os testes unitários da implementação.

## Corpus compartilhado

O índice [contracts/cases/index.json](../../contracts/cases/index.json) lista 19 casos JSON
em `autoscalers/contracts/cases/`. Cada fixture contém `id`, `operation`, `input`, `config`,
`state` e `expectedResult`. Os resultados registram o comportamento Python de referência
adotado como contrato Go.

As operações cobertas são `metric`, `evaluate`, `cpu_quantity`, `adapt_replicas`, `adapt_cpu`
e `adapt_tag`. Os casos incluem seleção da primeira métrica, tipos numéricos, limiares e banda
sem adaptação, prioridade/fallback das estratégias, cálculo de réplicas, conversão de CPU e
uma entrada completa para cada adaptador. Os campos `config` e `state` permanecem presentes
mesmo nos casos em que a operação não os utiliza.

## Entrega 3 — CLI, métrica, logs e configuração

`metric_test.go` consome os contratos de métrica. `app_test.go` cobre despacho da CLI,
argumentos inválidos e entradas; `config_test.go` cobre YAML válido, vazio, `null`, malformado
e ausente. A etapa de métrica é verificada com caminho de configuração inexistente para
garantir que não leia `/config.yaml`.

## Entrega 4 — avaliação e Kubernetes

`evaluate_test.go` consome os casos de avaliação e cobre conversão/truncamento de quantidades.
`cpu_quantity_test.go` consome os contratos de conversão para millicores.
`evaluate_runtime_test.go` testa a ordem Deployment → Pods → status, consulta de Pods em perfil
horizontal, persistência do baseline, configuração inválida, ausência de Pods, erros da API e
seleção de CPU entre Pods.

`initial_data_test.go` cobre leitura do status, JSON inválido, identidade ausente, merge,
releituras, corpo do patch, atraso de reconciliação e annotations ausentes. `kubernetes_test.go`
usa os fakes oficiais de `client-go` para verificar recursos, subrecursos, seletores,
sequência de chamadas, conversão de objetos e tipo de patch.

## Entrega 5A — adaptador de réplicas

`adapt_replicas_test.go` consome `adapt_replicas_success.json` e verifica GET antes da
validação, ordem GET/PATCH, preservação do Deployment carregado, alteração de
`spec.replicas`, saída sem newline, rejeição de valores que não são inteiros, aceitação de
booleanos conforme Python, saída vazia nas falhas de contexto/leitura e resultado de erro
quando o patch falha. `kubernetes_test.go` confirma o alvo, corpo completo e
`StrategicMergePatchType` do PATCH real exercitado pelo fake.

Os testes usam doubles e fakes em memória. Não acessam cluster, rede externa, Docker ou
ferramentas de carga. A suíte completa, `go vet` e build foram aprovados nesta entrega; os
comandos e a verificação RED → GREEN → RED → GREEN estão registrados em
[comandos-investigacao](comandos-investigacao.md).

## Subentregas 5B e 5C — adaptadores de CPU e tag

`adapt_cpu_test.go` consome `adapt_cpu_success.json` e cobre o resultado e os corpos enviados
a cada Pod, a ordem das duas listagens, o arredondamento ties-to-even do Python, o teto
`maxCPU`, o piso do CPU inicial, parâmetros inválidos, skip durante rollout, captura do valor
inicial no mesmo ciclo, lista de Pods vazia, CPU ausente/zero, multiplicadores finitos que
excedem `int64` e falha intermediária sem rollback. `kubernetes_test.go` verifica
`StrategicMergePatchType` e o subrecurso `resize`.

`adapt_tag_test.go` consome `adapt_tag_success.json` e cobre parsing de imagens, substituição
de tag, navegação e limites da escada, limite inicial, persistência da tag antes do patch,
parâmetro/container ausente, rollout, imagem inválida, falha do patch e `update_cpu` para todos
os containers. Também confirma que uma lista vazia de Pods deixa a troca da imagem seguir,
como no Python.

As duas suítes isolam API Kubernetes, arquivos, ambiente e logs em memória ou diretórios
temporários. Não acessam cluster, rede externa, Docker ou carga. A suíte completa, `go vet`,
build e formatação passaram; os dois adaptadores falharam quando seu fluxo foi temporariamente
retirado e passaram após restauração. Os comandos exatos constam no
[registro da subentrega](comandos-investigacao.md).

As subentregas 5B e 5C estão concluídas. A próxima entrega do plano é a 6 e aguarda instrução
explícita.
