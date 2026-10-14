# CSA Python HTTP

Implementação HTTP em Python. `main.py` inicializa a aplicação FastAPI e expõe `GET /health`. A coleta fica em `metrics.py` e a avaliação pura em `evaluate.py`. A adaptação de réplicas fica em `adapt_replicas.py`; coleta, avaliação e adaptação ainda não estão ligadas a uma rota ou ciclo HTTP. As adaptações de CPU e tag ficam para atividades posteriores.

`metrics.collect_metrics()` consulta a métrica externa configurada e lê `spec.replicas` do Deployment. Ela não consulta Pods nem CPU. `CSA_NAMESPACE`, `CSA_DEPLOYMENT_NAME` e `CSA_METRIC_NAME` são obrigatórios. `CSA_METRIC_SELECTOR` é opcional e `CSA_TARGET_VALUE` assume `1000` se não for definido. O cliente Kubernetes é configurado e criado na primeira coleta e reutilizado nas seguintes; o health check não inicializa o cliente.

`evaluate.evaluate(snapshot, config, current_mcpu, initial_mcpu)` recebe o snapshot da coleta e o estado de CPU e retorna a estratégia selecionada no formato `{strategy, parameters}`, ou `None` quando não há adaptação. A função não consulta Kubernetes.

`adapt_replicas.adapt_replicas(replicas)` aplica somente `spec.replicas` no Deployment indicado por `CSA_NAMESPACE` e `CSA_DEPLOYMENT_NAME`, usando o cliente Kubernetes lazy compartilhado. Ela retorna `{"replicas": <quantidade>}` e não lê o Deployment antes do patch.

## Empacotamento para testes futuros

`profiles/{h,hq,v,vq}.yaml` mantém os limites e a métrica do laboratório, variando `enabled_strategies` conforme os perfis CSA existentes. O `Dockerfile` seleciona um deles pelo argumento `PROFILE` e copia o resultado para `/config.yaml`. A aplicação ainda não carrega esse arquivo nem executa o ciclo periódico; somente o perfil H referencia uma adaptação implementada até agora.

Os manifests `custom-selfadapter-{h,hq,v,vq}.yaml` criam o Pod da implementação para testes futuros. Eles usam apenas o schema `CustomSelfAdapter` já publicado pelo operador, definem as configurações de ambiente da coleta e concedem leitura da métrica externa `znn_latency_ms_p95`. O operador já concede acesso ao Deployment de destino. O endpoint `/health` é a única rota disponível neste momento; os manifests não tornam a adaptação automática funcional.

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
