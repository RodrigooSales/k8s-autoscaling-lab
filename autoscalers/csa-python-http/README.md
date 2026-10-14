# CSA Python HTTP

Implementação HTTP em Python. `main.py` inicializa a aplicação FastAPI e expõe `GET /health`. A coleta fica em `metrics.py` e a avaliação pura em `evaluate.py`. A adaptação de réplicas fica em `adapt_replicas.py`; coleta, avaliação e adaptação ainda não estão ligadas a uma rota ou ciclo HTTP. As adaptações de CPU e tag ficam para atividades posteriores.

`metrics.collect_metrics()` consulta a métrica externa configurada e lê `spec.replicas` do Deployment. Ela não consulta Pods nem CPU. `CSA_NAMESPACE`, `CSA_DEPLOYMENT_NAME` e `CSA_METRIC_NAME` são obrigatórios. `CSA_METRIC_SELECTOR` é opcional e `CSA_TARGET_VALUE` assume `1000` se não for definido. O cliente Kubernetes é configurado e criado na primeira coleta e reutilizado nas seguintes; o health check não inicializa o cliente.

`evaluate.evaluate(snapshot, config, current_mcpu, initial_mcpu)` recebe o snapshot da coleta e o estado de CPU e retorna a estratégia selecionada no formato `{strategy, parameters}`, ou `None` quando não há adaptação. A função não consulta Kubernetes.

`adapt_replicas.adapt_replicas(replicas)` aplica somente `spec.replicas` no Deployment indicado por `CSA_NAMESPACE` e `CSA_DEPLOYMENT_NAME`, usando o cliente Kubernetes lazy compartilhado. Ela retorna `{"replicas": <quantidade>}` e não lê o Deployment antes do patch.

## Empacotamento para testes futuros

`profiles/{h,hq,v,vq}.yaml` usa a configuração da aplicação HTTP independente: intervalo, limites de réplicas/CPU, métrica (`name`, `selector`, `targetValue`) e `enabled_strategies`. Os perfis variam somente as estratégias conforme o cenário. Eles não incluem callbacks `metric`, `evaluate` ou `adapt` do executor antigo nem campos operacionais como `kubernetesMetricSpecs`, `requireKubernetesMetrics` e `logVerbosity`.

O operador consome o recurso `CustomSelfAdapter` para provisionar o Pod e o RBAC. Esse processo não cria um Service; a aplicação HTTP independente será o runtime do ciclo e não usa o executor genérico antigo. Os manifests `custom-selfadapter-{h,hq,v,vq}.yaml` configuram o Pod e as permissões necessárias para testes futuros.

O `Dockerfile` seleciona um profile pelo argumento `PROFILE` e o copia para `/config.yaml`. A aplicação ainda não carrega `/config.yaml` nem executa o loop periódico. `GET /health` continua sendo a única rota; somente a adaptação de réplicas está implementada, e ainda não está ligada a uma rota ou ciclo HTTP.

Para construir uma imagem local com um perfil, a partir da raiz do repositório:

```sh
docker build --build-arg PROFILE=h -t csa-python-http:h autoscalers/csa-python-http
```

O Dockerfile não publica a imagem. Antes de aplicar um manifest em cluster, publique a imagem no registry acessível aos nós e use a referência correspondente em `image`.

## Ambiente local

O worktree reutiliza a `.venv` da raiz do repositório por meio de um link simbólico. A partir desta pasta:

```sh
../../.venv/bin/python -m pip install -r requirements.txt
../../.venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8080
```

O endpoint inicial é `GET /health`. O histórico das decisões, comandos e validações fica em [docs/csa-python-http/historico-de-desenvolvimento.md](../../docs/csa-python-http/historico-de-desenvolvimento.md).
