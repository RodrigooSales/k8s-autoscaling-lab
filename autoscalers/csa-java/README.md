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
  - Usa `/config.yaml` (`enabled_strategies`, `minReplicas`, `maxReplicas`, `maxCPU`).
  - Aceita `hints` (testes de contrato) e tambem tenta obter estado real no cluster quando executando in-cluster.
- `adapt_*`
  - `adapt_replicas`: implementado.
  - `adapt_tag`: implementado.
  - `adapt_cpu`: implementado.
  - `initial_data`: implementado via `InitialDataStore`.

## Paridade funcional atual

- `metric` e `evaluate`: alinhados nos caminhos exercitados pelo experimento e cobertos por fixtures estaticas.
- `config.yaml`: valores comportamentais comparados automaticamente com a configuracao Python.
- `adapt_replicas`, `adapt_cpu` e `adapt_tag`: fluxo Kubernetes coberto por traces HTTP estaticos equivalentes ao Python.
- `initialData`: sem cache local; releitura, merge e PATCH do Pod seguem o fluxo Python.
- Processo: stdout, codigos de saida e logs seguem o Python nos caminhos dos experimentos e falhas cobertas.
- Diferencas conhecidas (edge cases):
  - configuracoes invalidas incomuns e entradas fora dos cenarios reais nao sao alvo de paridade exaustiva.

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
- `org.csajava.logging`
  - `AdapterLogger`: formato e destinos de log equivalentes ao Python.

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
- `ConfigParityTest`: compara timeouts, limites, metrica, intervalo e estrategias com o Python.
- `QuantityParityTest`: cobre os formatos de metrica usados pelo experimento.
- `Adapt*RuntimeTest`: compara ordem, metodo, endpoint e payload sem exigir cluster real.
- `InitialDataStoreTest`: cobre atraso de reconciliacao, merge, sequencia HTTP e falhas de persistencia.
- `AppTest`, `JsonOutTest` e `RuntimeLoggingTest`: cobrem stdout, exit code e logs normalizados.

Os testes Java nao executam scripts Python. A validacao in-cluster continua necessaria para os efeitos Kubernetes.

Comandos:

```bash
./gradlew test
./gradlew build
```

## Infra para cluster

- Repositorio de imagem da versao Java: `registry.k8s.lab/csa-znn-java`.
- Manifests de infraestrutura:
  - `custom-selfadapter-h.yaml`
  - `custom-selfadapter-hq.yaml`
  - `custom-selfadapter-v.yaml`
  - `custom-selfadapter-vq.yaml`
- O fluxo segue o mesmo padrao do CSA Python: build de uma tag por vez usando `IMAGE` e `TAG`.

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

Build para cluster (mesmo padrao do CSA Python):

```bash
# default (TAG=vq)
./build_csa-java.sh

# versoes por tag
TAG=h ./build_csa-java.sh
TAG=hq ./build_csa-java.sh
TAG=v ./build_csa-java.sh
TAG=vq ./build_csa-java.sh
```

## Proximos passos do plano

1. Rodar validacao funcional progressiva no cluster (canario por perfil).
2. Adicionar `csa-java` aos testes existentes do repositorio sem remover os cenarios atuais.
3. Executar rodada massiva da pesquisa e consolidar resultados.
