# Estrutura da implementação CSA Python

Este documento descreve os arquivos versionados em
[`autoscalers/csa`](../../autoscalers/csa/). A implementação é composta por scripts
chamados pelo operador CSA: `metric`, `evaluate`, `adapt_replicas`, `adapt_cpu` e
`adapt_tag`.

## Código Python

- [`main.py`](../../autoscalers/csa/main.py) é um programa standalone que imprime uma mensagem e aguarda em loop. O Dockerfile atual não o copia para a imagem nem o configura como etapa do CSA.
- [`scripts/adapter_logger.py`](../../autoscalers/csa/scripts/adapter_logger.py) configura os logs do adaptador para stderr e `/tmp/adapter.log`.
- [`scripts/adapt_base.py`](../../autoscalers/csa/scripts/adapt_base.py) contém o contexto compartilhado pelos adaptadores: parsing do stdin, configuração in-cluster, leitura do Deployment e Pods, verificação de rollout e conversão de CPU.
- [`scripts/metric_nginx_req_duration.py`](../../autoscalers/csa/scripts/metric_nginx_req_duration.py) extrai réplicas atuais e os valores atual e alvo da primeira métrica para a etapa de avaliação.
- [`scripts/evaluate.py`](../../autoscalers/csa/scripts/evaluate.py) calcula a razão de carga, respeita os limites configurados e escolhe entre réplicas, CPU e tag conforme estratégias habilitadas.
- [`scripts/adapt_replicas.py`](../../autoscalers/csa/scripts/adapt_replicas.py) aplica ao Deployment a quantidade de réplicas selecionada pela avaliação.
- [`scripts/adapt_cpu.py`](../../autoscalers/csa/scripts/adapt_cpu.py) calcula o novo limite de CPU, respeita o baseline e `maxCPU`, e redimensiona os containers `znn` e `nginx` dos Pods.
- [`scripts/adapt_tag.py`](../../autoscalers/csa/scripts/adapt_tag.py) percorre a escada de tags da imagem do container `znn` e pode sincronizar os limites de CPU no template do Deployment.
- [`scripts/initial_data.py`](../../autoscalers/csa/scripts/initial_data.py) lê `status.initialData` do recurso CSA e persiste os valores iniciais de tag e CPU pela anotação do Pod do adaptador.

Não há arquivos de teste dentro de `autoscalers/csa`. Os casos de contrato compartilhados pelas implementações ficam em [`autoscalers/contracts/cases`](../../autoscalers/contracts/cases/).

## Configuração e empacotamento

- [`config.yaml`](../../autoscalers/csa/config.yaml) liga cada etapa do CSA ao script correspondente e define intervalo, timeouts, limites, métrica externa e estratégias habilitadas.
- [`requirements.txt`](../../autoscalers/csa/requirements.txt) declara os pacotes Python usados pela imagem: cliente Kubernetes e PyYAML.
- [`Dockerfile`](../../autoscalers/csa/Dockerfile) instala as dependências e copia a configuração e os scripts para a imagem runtime do Custom Self Adapter.
- [`build_csa-python.sh`](../../autoscalers/csa/build_csa-python.sh) constrói e publica a imagem Python e renderiza o manifesto da tag selecionada.

### Manifests do CustomSelfAdapter

Os manifests apontam para o Deployment `kube-znn`, definem o container CSA Python e incluem permissão para `pods/resize`. Os arquivos por perfil selecionam as imagens publicadas com as respectivas tags.

- [`custom-selfadapter-h.yaml`](../../autoscalers/csa/custom-selfadapter-h.yaml) seleciona o perfil horizontal `h`.
- [`custom-selfadapter-hq.yaml`](../../autoscalers/csa/custom-selfadapter-hq.yaml) seleciona o perfil horizontal com adaptação de qualidade `hq`.
- [`custom-selfadapter-v.yaml`](../../autoscalers/csa/custom-selfadapter-v.yaml) seleciona o perfil vertical `v`.
- [`custom-selfadapter-vq.yaml`](../../autoscalers/csa/custom-selfadapter-vq.yaml) seleciona o perfil vertical com adaptação de qualidade `vq`.
- [`custom-selfadapter-template.yaml`](../../autoscalers/csa/custom-selfadapter-template.yaml) é o modelo preenchido pelo script de build com repositório e tag da imagem.
