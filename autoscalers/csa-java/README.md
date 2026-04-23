# CSA Java

Implementacao Java do Custom Self Adapter (CSA), migrada por etapas a partir da versao Python em `autoscalers/csa`.

## Status da migracao

- [x] Contratos de entrada e saida congelados em `contracts/`.
- [x] Runtime base Java (CLI, stdin/stdout, config, dispatcher).
- [x] Migracao de `metric` com paridade de contrato.
- [x] Migracao de `evaluate` com paridade de contrato.
- [x] Migracao de `adapt_replicas`.
- [x] Migracao de `adapt_tag`.
- [x] Migracao de `adapt_cpu` e `initial_data`.
- [ ] Validacao funcional progressiva no cluster.
- [ ] Integrar `csa-java` ao pipeline de testes ja existente no repositorio.
- [ ] Rodada massiva da pesquisa com a estrutura atual de coleta e analise.

## O que ja esta implementado

- `metric` (`-m metric`)
  - Le `resource.spec.replicas` e `kubernetesMetrics[0]`.
  - Retorna `current_replicas`, `target_value`, `current_value`.
- `evaluate` (`-m evaluate`)
  - Replica a logica do Python para decidir `adapt_replicas`, `adapt_cpu` ou `adapt_tag`.
  - Usa `config.yaml` (`enabled_strategies`, `minReplicas`, `maxReplicas`, `maxCPU`).
  - Aceita `hints` (testes de contrato) e tambem tenta obter estado real no cluster quando executando in-cluster.
- `adapt_*`
  - `adapt_replicas`: implementado.
  - `adapt_tag`: implementado.
  - `adapt_cpu`: implementado.
  - `initial_data`: implementado via `InitialDataStore`.

## Paridade funcional atual

- Estado geral: muito proximo da implementacao Python para os fluxos principais.
- Cobertura automatizada: fixtures de contrato para `metric`, `evaluate` e `adapt_*`.
- Diferencas conhecidas (edge cases):
  - `evaluate` sem adaptacao: Python tende a nao escrever saida; Java retorna `{"result":"skip", ...}`.
  - baseline de `initial_mcpu` em `evaluate`: Python usa `initial_data`; Java hoje usa fallback pela spec do deployment quando necessario.
  - entradas invalidas: em alguns caminhos o Python retorna sem JSON; Java tende a retornar `{"result":"error"}`.
  - parser de imagem em `adapt_tag`: Java usa parser simplificado, suficiente para os cenarios atuais, mas menos flexivel que o regex do Python.

## Estrutura de pacotes

- `org.csajava`
  - `App`: entrypoint e orquestracao da execucao.
- `org.csajava.cli`
  - `Cli`, `Mode`: contrato da CLI e resolucao de modo.
- `org.csajava.io`
  - `Stdin`, `JsonOut`: borda de entrada/saida.
- `org.csajava.context`
  - `RuntimeContext`: `stdinRaw`, `stdinJson`, `config`, `hints`.
- `org.csajava.config`
  - `ConfigLoader`: leitura de `/config.yaml` com fallback local.
- `org.csajava.runtime`
  - `ModeHandler`, `ModeHandlers`: dispatch por modo.
- `org.csajava.runtime.adapt`
  - `AdaptSupport`: helpers comuns para os modos `adapt_*`.
- `org.csajava.runtime.adapt.replicas`
  - `AdaptReplicasRuntime`: equivalente ao script Python de adapt_replicas.
- `org.csajava.runtime.adapt.tag`
  - `AdaptTagRuntime`: equivalente ao script Python de adapt_tag.
- `org.csajava.runtime.adapt.cpu`
  - `AdaptCpuRuntime`: equivalente ao script Python de adapt_cpu.
- `org.csajava.runtime.metric`
  - `MetricRuntime`: equivalente ao script Python de metric.
- `org.csajava.runtime.evaluate`
  - `EvaluateRuntime`: equivalente ao script Python de evaluate.
- `org.csajava.runtime.initialdata`
  - `InitialDataStore`: equivalente funcional do script Python `initial_data.py`.
- `org.csajava.util`
  - `JsonUtil`, `YamlMap`, `CpuQuantity`: utilitarios compartilhados.
- `org.csajava.model`
  - `ResultError`: payload padrao de erro.

## Mapeamento Python -> Java

- `scripts/metric_nginx_req_duration.py` -> `runtime/metric/MetricRuntime`
- `scripts/evaluate.py` -> `runtime/evaluate/EvaluateRuntime`
- `scripts/adapt_replicas.py` -> `runtime/adapt/replicas/AdaptReplicasRuntime`
- `scripts/adapt_tag.py` -> `runtime/adapt/tag/AdaptTagRuntime`
- `scripts/adapt_cpu.py` -> `runtime/adapt/cpu/AdaptCpuRuntime`
- `scripts/initial_data.py` -> `runtime/initialdata/InitialDataStore`

## Testes e validacao

- `CliTest`: parse de argumentos e erros de uso.
- `CpuQuantityTest`: parse/format de CPU.
- `RuntimeContextTest`: parse de stdin e carga de config.
- `ContractParityTest`: paridade de `metric`, `evaluate` e `adapt_*` com fixtures em `contracts/cases/`.

Obs: a paridade coberta por fixture garante os cenarios congelados do projeto. Ainda ha comportamentos de borda que devem ser validados no cluster.

Comandos:

```bash
./gradlew test
./gradlew build
```

## Execucao local

Exemplo direto com fixture de contrato:

```bash
jq -c '.stdin' contracts/cases/metric.basic.json | ./gradlew run --args='-m metric'
jq -c '.stdin' contracts/cases/evaluate.high_load_cpu.json | ./gradlew run --args='-m evaluate'
```

## Docker

Build e smoke test:

```bash
docker build -t csa-java:test .
printf '%s' '{"resource":{"spec":{"replicas":2}},"kubernetesMetrics":[{"spec":{"external":{"target":{"value":"1000"}}},"external":{"current":{"value":"1300000"}}}]}' | docker run --rm -i csa-java:test /app/csa-java/bin/csa-java -m metric
```

## Proximos passos do plano

1. Rodar validacao funcional progressiva no cluster (canario por perfil).
2. Adicionar `csa-java` aos testes existentes do repositorio sem remover os cenarios atuais.
3. Executar rodada massiva da pesquisa e consolidar resultados.
