# CSA Python HTTP: histórico de desenvolvimento

## Preparação do ambiente

### Decisões

- A implementação fica isolada na branch `feat/csa-python-http` e no worktree `.worktrees/csa-python-http`, criado a partir de `dev`.
- A estrutura de entrada é `autoscalers/csa-python-http/` e esta pasta guarda o histórico próprio da implementação.
- FastAPI fornece a aplicação ASGI com um health check mínimo. `requirements.txt` declara somente FastAPI 0.143.0 e Uvicorn 0.54.0; a lógica do autoscaler e dependências Kubernetes ficam para atividades posteriores.
- O worktree reutiliza a `.venv` local e o ambiente Vagrant do checkout principal por links simbólicos, sem criar cópias ou inicializar os submódulos novamente.
- O primeiro endpoint é `GET /health`, com resposta `{"status":"UP"}`. Ainda não há ciclo de coleta, avaliação ou adaptação.
- As dependências diretas foram fixadas nas versões instaladas na `.venv` compartilhada. O lock transitivo e a imagem de execução ficam para quando o empacotamento for definido.

### Comandos usados

Executados na raiz do repositório:

```sh
git worktree add -b feat/csa-python-http .worktrees/csa-python-http dev
ln -s /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/.venv .worktrees/csa-python-http/.venv
ln -s /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes .worktrees/csa-python-http/.vagrant-env
mkdir -p .worktrees/csa-python-http/autoscalers/csa-python-http .worktrees/csa-python-http/docs/csa-python-http
.venv/bin/python -m pip install -r .worktrees/csa-python-http/autoscalers/csa-python-http/requirements.txt
```

Ambiente observado: Python 3.13.11 na `.venv` reutilizada.

### Validação

FastAPI 0.143.0 e Uvicorn 0.54.0 foram instalados na `.venv` compartilhada. A primeira tentativa sem acesso de rede não alcançou o índice de pacotes; a instalação autorizada com acesso à rede concluiu. O esqueleto foi revisado estaticamente; nenhum teste foi criado ou executado, nem cluster ou Vagrant foi iniciado nesta preparação.

## Atividade 1: coleta de métricas

### Decisões

- `main.py` inicializa a aplicação FastAPI e expõe somente `GET /health`. Toda coleta, tipos e configuração ficam em `autoscalers/csa-python-http/metrics.py`, com `collect_metrics()` pronta para ser chamada pelo ciclo nas próximas atividades.
- O resultado é um `MetricSnapshot` imutável com réplicas atuais, valor atual da métrica em milésimos e alvo inteiro. `MetricCollectionError` distingue falhas de coleta, incluindo uma resposta sem valores.
- A coleta consulta primeiro `external.metrics.k8s.io/v1beta1`, soma os valores de todos os `items` com o arredondamento para cima em milésimos aplicado a cada item, e só então lê `spec.replicas` do Deployment. Uma lista vazia encerra a coleta antes da leitura do Deployment. Não há consulta de Pods ou CPU.
- `CSA_NAMESPACE`, `CSA_DEPLOYMENT_NAME` e `CSA_METRIC_NAME` são obrigatórios e rejeitam ausência ou valor vazio. `CSA_METRIC_SELECTOR` é opcional e só é enviado quando contém valor não vazio. `CSA_TARGET_VALUE` assume `1000` por padrão e é mantido em unidades inteiras, sem conversão para milésimos; valores configurados com parte fracionária são rejeitados.
- `kubernetes==35.0.0` foi adicionado às dependências, alinhado ao Kubernetes 1.35 documentado para o laboratório. O primeiro `collect_metrics()` configura e cria um único `ApiClient` sob demanda; um lock protege essa inicialização e as coletas seguintes reutilizam o mesmo cliente. As APIs de métricas externas e de Deployments compartilham esse cliente. Importar a aplicação e responder ao health check não exigem kubeconfig nem acesso ao cluster.
- O teste usa o cliente Kubernetes real com `client.Configuration.host` definido diretamente para o servidor HTTP local da fixture. Os loaders de configuração foram substituídos para não consultar configuração do usuário, e `KUBECONFIG` é removida do ambiente de teste.

### Comandos usados

Executados a partir da raiz do worktree `.worktrees/csa-python-http`:

```sh
../../.venv/bin/python -m pip install -r autoscalers/csa-python-http/requirements.txt
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_metric_collection.py::test_collects_metric_before_deployment_and_returns_milli_snapshot
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_metric_collection.py::test_reuses_lazily_created_kubernetes_client_for_multiple_collections
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_metric_collection.py::test_required_settings_reject_missing_or_empty_values
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_metric_collection.py
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_metric_collection.py::test_empty_metric_list_fails_before_reading_deployment
```

A instalação sem acesso de rede foi bloqueada pelo sandbox; a repetição autorizada instalou `kubernetes==35.0.0` e suas dependências na `.venv` compartilhada. O sandbox também bloqueia sockets locais; os comandos de teste foram executados com a permissão necessária para abrir somente os servidores HTTP locais das fixtures.

### Validação e isolamento

- **RED inicial:** o teste contra o código existente falhou porque `main.collect_metrics()` ainda não existia (`AttributeError`).
- **RED de reutilização:** adicionei um teste que chama a coleta duas vezes e conta chamadas ao loader de configuração e criações de `ApiClient`. Antes da reutilização, falhou porque o loader foi chamado 2 vezes em vez de 1.
- **RED de configuração obrigatória:** os seis casos parametrizados para variáveis ausentes ou vazias falharam, pois a coleta usava defaults ou só falhava depois de chamar a API. A validação agora ocorre antes de inicializar o cliente.
- **GREEN:** após a inicialização lazy, protegida por lock, a reutilização do `ApiClient` e a validação das três configurações obrigatórias, a suíte com configuração explícita para o servidor local passou com `11 passed`. Os testes confirmaram ordem métrica/Deployment, soma e conversão para milésimos, alvo padrão `1000`, alvo configurado inteiro exato, seletor opcional, erros para configurações ausentes/vazias antes de qualquer chamada, erro na lista vazia sem chamada ao Deployment, reutilização do cliente e health sem inicializá-lo.
- **RED de regressão:** removi temporariamente a validação da lista vazia. O teste específico falhou com `DID NOT RAISE` e registrou chamadas locais à métrica e ao Deployment, comprovando que detecta a regressão. Restaurei a validação; a suíte final, incluindo os testes de reutilização e configuração obrigatória, passou com `11 passed`.
- **Desvio no primeiro teste fora do sandbox:** a primeira configuração da fixture usava um kubeconfig temporário pela variável `KUBECONFIG`, mas o cliente Kubernetes havia capturado o caminho padrão do usuário ao ser importado. A execução consultou o endpoint que já estava nesse kubeconfig, recebeu uma lista de métrica sem valores e não chegou à leitura do Deployment. Não houve patch nem outra operação de escrita. Corrigi o isolamento configurando diretamente `client.Configuration.host` com a URL efêmera do servidor local, removendo `KUBECONFIG` e substituindo os loaders; todas as execuções locais posteriores usaram apenas essa configuração explícita.
- Nenhum teste de cluster, Vagrant ou carga foi executado deliberadamente. Nenhuma alteração foi commitada ou enviada.

### Separação da aplicação e coleta

- A coleta foi movida integralmente de `main.py` para `metrics.py`. `main.py` ficou apenas com a criação do objeto `FastAPI` e a rota `GET /health`; `metrics.py` contém `MetricSnapshot`, `MetricCollectionError`, leitura das variáveis, cliente lazy reutilizado e chamadas ao Kubernetes.
- O comando de inicialização HTTP não muda porque a aplicação continua em `main.py`: `../../.venv/bin/python -m uvicorn main:app --host 0.0.0.0 --port 8080`. Os testes continuam no mesmo caminho e agora importam `metrics` para a coleta e `main` somente para verificar health.
- Validação após a separação: `../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_metric_collection.py` passou com `11 passed`, usando somente o servidor HTTP local configurado diretamente no cliente.

## Atividade 2: avaliação

### Decisões

- A regra pura fica em `autoscalers/csa-python-http/evaluate.py`. `evaluate()` recebe `MetricSnapshot`, a configuração e os valores atual/baseline de CPU; não faz chamadas Kubernetes nem adiciona rota HTTP. O retorno mantém `{strategy, parameters}` e usa `None` quando nenhuma estratégia é selecionada.
- A razão é `current_metric_value / (target_metric_value * 1000)`. Alvo zero gera `EvaluationError`. A função aplica os limites e a ordem já definidos nos contratos: réplicas, CPU e tag para carga `>= 0.95`; reduz na ordem inversa para carga `< 0.90`; a faixa intermediária não seleciona estratégia. O número desejado de réplicas usa `ceil`, limitado ao máximo ou mínimo conforme a direção.
- O fallback de tag em alta carga informa `tag_up=false` e `update_cpu` conforme a presença da estratégia de CPU. O fallback de baixa carga informa somente `tag_up=true`, como nos contratos compartilhados.
- `tests/test_evaluate.py` lê os 10 casos `evaluate_*` indicados em `autoscalers/contracts/cases/index.json`, em vez de duplicar os resultados esperados em testes locais. Casos locais adicionais cobrem alvo zero e tag de alta carga com estratégia de CPU desabilitada.

### Comandos usados

Executados a partir da raiz do worktree `.worktrees/csa-python-http`:

```sh
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_evaluate.py
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_evaluate.py::test_zero_target_metric_raises_specific_error
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_evaluate.py::test_replica_decision_respects_configured_bounds
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests
```

### Validação

- **RED inicial:** o teste foi escrito antes de `evaluate.py`; a coleta de `test_evaluate.py` falhou com `ModuleNotFoundError: No module named 'evaluate'`.
- **GREEN:** depois da implementação mínima, os casos de contrato, alvo zero e fallback de tag passaram. A suíte completa Python do projeto passou com `14 passed`.
- **RED de regressão:** alterei temporariamente a condição de erro de alvo zero para aceitar o valor zero. O teste específico falhou com `ZeroDivisionError`, confirmando que cobre a regressão. Restaurei a condição e executei a suíte completa novamente; ela terminou com `14 passed`.
- Para os limites de réplicas, acrescentei casos locais para o teto (6 desejadas limitadas a 5) e o piso (1 desejada elevada a 2). Ao remover temporariamente os dois clamps, ambos falharam com os valores sem limite; após restaurá-los, os dois casos passaram.
- A primeira tentativa da suíte completa dentro do sandbox passou nos testes puros, mas não conseguiu abrir os sockets loopback exigidos pela fixture HTTP local da coleta (`PermissionError`). A execução autorizada seguinte passou com `14 passed`; após acrescentar os dois casos de limites, a execução final passou com `16 passed`. A fixture define o cliente diretamente para o servidor local, substitui os loaders de configuração e remove `KUBECONFIG`; nenhum cluster foi consultado. Os testes da avaliação não abrem conexões.
- Nenhuma rota foi adicionada para avaliação. `main.py` e `metrics.py` permaneceram intactos nesta atividade; adaptações, commit, push e PR continuam fora do escopo.

## Atividade 3: adaptação de réplicas

### Decisões

- Esta etapa implementa somente `autoscalers/csa-python-http/adapt_replicas.py`. CPU, tag, rotas e ciclo de controle não fazem parte desta atividade.
- `adapt_replicas(replicas)` lê `CSA_NAMESPACE` e `CSA_DEPLOYMENT_NAME`, reutiliza o cliente lazy de `metrics.py` e envia um patch estratégico mínimo com apenas `spec.replicas`. A leitura prévia do Deployment foi removida porque a operação não precisa de seu estado; isso evita uma chamada Kubernetes e reduz o corpo enviado.
- O retorno de sucesso mantém o contrato `{"replicas": <quantidade>}`. A quantidade deve ser inteira, como no script de referência; falhas de configuração/API são propagadas como `AdaptationError` para o chamador decidir a resposta HTTP. A adaptação de réplicas não adiciona guarda de rollout, seguindo o comportamento Python existente.
- Os testes usam fakes locais das APIs Kubernetes. Eles verificam namespace/Deployment configurados, corpo exato do patch, rejeição de quantidade não inteira antes de inicializar o cliente e propagação específica de erro da API. Não há acesso a cluster.

### Comandos usados

Executados a partir da raiz do worktree `.worktrees/csa-python-http`:

```sh
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests/test_adapt_replicas.py
../../.venv/bin/python -m pytest -q autoscalers/csa-python-http/tests
```

### Validação

- **RED:** o teste foi escrito antes da implementação e falhou na coleta com `ModuleNotFoundError: No module named 'adapt_replicas'`.
- **GREEN:** após a implementação mínima, `test_adapt_replicas.py` passou com `3 passed`.
- **RED de regressão:** alterei temporariamente o valor enviado no patch para `replicas + 1`. O teste do corpo do patch falhou mostrando `5` quando solicitava `4`. Restaurei o valor original e o arquivo de teste passou novamente com `3 passed`.
- A suíte Python completa passou com `19 passed`, incluindo os testes de coleta local e os novos fakes Kubernetes. Foi necessária permissão para os sockets loopback usados pela fixture de coleta; o teste de adaptação usa somente fakes e nenhum kubeconfig ou cluster foi consultado. A validação não iniciou Vagrant nem testes de carga. `main.py`, `metrics.py` e `evaluate.py` não foram alterados nesta atividade; nenhum arquivo foi staged, commitado ou enviado.

## Validação funcional sequencial no cluster

O teste foi executado em 13/10/2026 no cluster Vagrant existente, usando `vagrant-kubeadm-kubernetes/configs/config` e o contexto `kubernetes-admin@kubernetes`. Os quatro nós responderam. Para isolar a escrita, foi criada a namespace `csa-replica-smoke-20261013` e um Deployment `replica-target` com zero réplicas e `nodeSelector` exclusivo do teste. Python, Go e Java foram exercitados em sequência contra esse mesmo Deployment; `kube-znn` não foi alterado e nenhuma matriz de carga foi executada.

Fixture criada:

```sh
kubectl --kubeconfig /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config create namespace csa-replica-smoke-20261013
kubectl --kubeconfig /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config apply -f - <<'YAML'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: replica-target
  namespace: csa-replica-smoke-20261013
spec:
  replicas: 0
  selector:
    matchLabels:
      app: replica-target
  template:
    metadata:
      labels:
        app: replica-target
    spec:
      nodeSelector:
        csa-replica-smoke-20261013: test-only
      containers:
        - name: pause
          image: registry.k8s.io/pause:3.10
YAML
```

Comandos Python, executados a partir da raiz do worktree `.worktrees/csa-python-http`:

```sh
CSA_NAMESPACE=csa-replica-smoke-20261013 CSA_DEPLOYMENT_NAME=replica-target KUBECONFIG=/home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config ../../.venv/bin/python -c 'import sys; sys.path.insert(0, "autoscalers/csa-python-http"); from adapt_replicas import adapt_replicas; print(adapt_replicas(2))'
kubectl --kubeconfig /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config -n csa-replica-smoke-20261013 get deployment replica-target -o jsonpath='{.spec.replicas}'
CSA_NAMESPACE=csa-replica-smoke-20261013 CSA_DEPLOYMENT_NAME=replica-target KUBECONFIG=/home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config ../../.venv/bin/python -c 'import sys; sys.path.insert(0, "autoscalers/csa-python-http"); from adapt_replicas import adapt_replicas; print(adapt_replicas(0))'
```

O adaptador retornou `{'replicas': 2}`, a leitura do Deployment confirmou `2`, e o adaptador retornou `{'replicas': 0}` ao restaurá-lo. A validação Go e Java, executada em seguida contra a mesma fixture, está registrada nos históricos próprios dessas implementações. A namespace foi removida depois:

```sh
kubectl --kubeconfig /home/rodrigo/Work/pesquisa/custom-self-adapter/k8s-autoscaling-lab/vagrant-kubeadm-kubernetes/configs/config delete namespace csa-replica-smoke-20261013 --wait=true --timeout=60s
```

## Atividade 4: empacotamento para testes futuros

### Decisões

- O CRD `CustomSelfAdapter` é compartilhado e já está instalado pelo chart em `charts/custom-self-adapter-operator/templates/crd/customselfadapters.custom-self-adapter.net.yaml`. Esta atividade adiciona apenas os objetos `CustomSelfAdapter`, usando os campos já definidos pelo CRD.
- A imagem contém um único container com a aplicação HTTP independente. Ela não inclui nem configura callbacks `type: http` do executor genérico e não adiciona um segundo container. O ciclo próprio da aplicação e o carregamento do profile ainda não foram implementados.
- `profiles/{h,hq,v,vq}.yaml` preserva intervalo, métrica externa e limites do laboratório, com os conjuntos de estratégias usados pelas implementações CSA atuais. Apenas a adaptação de réplicas está implementada; hq ainda requer adaptação de tag, e v/vq requerem CPU, que permanecem pendentes.
- O Dockerfile seleciona `PROFILE` durante o build e copia o YAML selecionado para `/config.yaml`, o caminho convencional do projeto. Atualmente a aplicação não lê esse arquivo. O comando de build documentado é local e não publica a imagem.
- Os manifests definem as variáveis de coleta necessárias. `CSA_NAMESPACE` é injetada pelo operador a partir do namespace do objeto. O probe usa o endpoint existente `/health`; isso permite verificar que o servidor iniciou, sem afirmar que a coleta, avaliação ou adaptação já funcionam como ciclo.
- O papel evita `roleRequiresMetricsServer: true`, que concede permissões amplas nos grupos de métricas. `additionalRoleRules` concede somente `get` e `list` sobre `external.metrics.k8s.io/znn_latency_ms_p95`. O operador já inclui permissões para ler e atualizar o Deployment alvo. O recurso não pede permissão `pods/resize`, pois a etapa atual adapta apenas réplicas.
- Os quatro objetos usam o mesmo nome `csa-znn-python-http`; aplique um perfil por vez. Os manifests concretos apontam para tags distintas da imagem `registry.k8s.lab/csa-python-http`.

### Comandos usados e validação

Executados a partir da raiz do worktree `.worktrees/csa-python-http`:

```sh
../../.venv/bin/python -c 'from pathlib import Path; import yaml; files = sorted(Path("autoscalers/csa-python-http/profiles").glob("*.yaml")) + sorted(Path("autoscalers/csa-python-http").glob("custom-selfadapter-*.yaml")); [yaml.safe_load(path.read_text()) for path in files]; print(f"valid YAML: {len(files)} profiles/manifests")'
git diff --check
```

## Atividade 5: schema próprio dos profiles HTTP

### Decisões

- Os quatro profiles agora usam o mesmo schema próprio da aplicação independente: `interval`, `minReplicas`, `maxReplicas`, `maxCPU`, `metric.name`, `metric.selector`, `metric.targetValue` e `enabled_strategies`. Os valores atuais são intervalo de 5000 ms, réplicas de 1 a 5, CPU máxima de 750 mCPU e métrica `znn_latency_ms_p95` com seletor vazio e alvo 1000.
- H habilita `adapt_replicas`; HQ habilita réplicas e tag; V habilita CPU; VQ habilita CPU e tag. Somente a função de adaptação de réplicas existe nesta etapa. CPU e tag não estão implementadas.
- Foram removidos `kubernetesMetricSpecs`, `requireKubernetesMetrics` e `logVerbosity`, campos ligados ao runtime anterior. A aplicação independente será responsável por seu ciclo e o operador continua responsável por criar o Pod/RBAC. Não foram adicionados callbacks `metric`, `evaluate` ou `adapt` nem um recurso Service.
- O `Dockerfile` ainda copia o profile para `/config.yaml`, mas a aplicação atual não carrega esse arquivo e ainda não executa um loop. Por isso, `metrics.py` continua usando `CSA_NAMESPACE`, `CSA_DEPLOYMENT_NAME`, `CSA_METRIC_NAME`, `CSA_METRIC_SELECTOR` e `CSA_TARGET_VALUE` do ambiente. O operador injeta o namespace; os manifests mantêm configuráveis o Deployment e a métrica. O campo `metric` do profile só passará a controlar a coleta quando o carregador for implementado.

### Comandos usados e validação

Executados a partir da raiz do worktree `.worktrees/csa-python-http-profiles`:

```sh
../../.venv/bin/python -c 'from pathlib import Path; import yaml; files = sorted(Path("autoscalers/csa-python-http/profiles").glob("*.yaml")); [yaml.safe_load(path.read_text()) for path in files]; print(f"valid profiles: {len(files)}")'
git diff --check
```

Os quatro profiles foram lidos como YAML válido e `git diff --check` não apontou problemas de whitespace. Não foram executados testes nem build. O comando de build para uso futuro permanece `docker build --build-arg PROFILE=h -t csa-python-http:h autoscalers/csa-python-http`; ele não foi executado nem publicou imagem.

## Atividade 7 — callbacks HTTP pelo runtime genérico

### Decisões

- O Pod Python permanece separado do Pod do runtime genérico. O operador gerencia ambos como `CustomSelfAdapter`; o runtime coleta a métrica e chama a API Python pelo `Service` `csa-python-http-api`.
- Cada profile mantém a estrutura de `metric`, `evaluate`, `adapt`, `kubernetesMetricSpecs`, limites e intervalo do runtime existente. As URLs dos callbacks usam o DNS do Service. O ConfigMap `csa-python-http-runtime-config-<perfil>` monta o mesmo profile em `/config.yaml` no Pod do runtime.
- O Service seleciona o Pod Python pelas labels `app.kubernetes.io/managed-by` e `v1.custom-resources-adapter.net/owned-by` aplicadas pelo operador. Só o runtime recebe acesso ao servidor de métricas. O Pod Python recebe o payload coletado pelo runtime e não consulta a métrica externa novamente.
- A avaliação sem decisão retorna `{}`. O runtime genérico passa a aceitar tanto esse objeto como stdout vazio dos callbacks shell, representa ambos como avaliação sem estratégia e encerra o ciclo sem chamar `Adapt`. Isso alinha o resultado sem ação entre os dois protocolos e corrige a falha de parse que ocorria com stdout vazio.
- Nesta etapa, todos os profiles habilitam apenas `adapt_replicas`. CPU e tag continuam fora do escopo implementado.
- Os manifests usam a imagem `registry.k8s.lab/custom-self-adapter:alpine-empty-evaluation`. Ela depende da construção e publicação do runtime atualizado; essa publicação e o teste no cluster não foram executados.

### Validação e comandos

O teste do callback primeiro falhou porque a API devolvia uma estratégia extra; passou após `/evaluate` devolver `{}`. Ao reverter temporariamente a correção, voltou a falhar. A suíte completa passou:

```sh
../../.venv/bin/pytest autoscalers/csa-python-http/tests -q
../../.venv/bin/python -c 'from pathlib import Path; import yaml; base=Path("autoscalers/csa-python-http"); files=sorted(base.glob("profiles/*.yaml"))+sorted(base.glob("custom-selfadapter-*.yaml"))+[base/"service.yaml"]; [list(yaml.safe_load_all(path.read_text())) for path in files]; print(f"parsed {len(files)} YAML files")'
git diff --check
```

Resultado: `17 passed`; 10 YAMLs foram parseados. No worktree local `../custom-self-adapter-empty-evaluation`, `go test ./internal/evaluatecalc ./internal/adapting -count=1` passou após demonstrar RED e GREEN para stdout vazio e `{}`. `go test ./...` também passou no runtime. Não foram publicados containers nem aplicados recursos Kubernetes.

## Correção do fluxo HTTP e dos profiles por cenário

### Decisões

- Os profiles preservam o schema do runtime usado pelas implementações shell: `metric`, `evaluate`, `adapt`, `interval`, limites, `kubernetesMetricSpecs`, `enabled_strategies`, `requireKubernetesMetrics` e `logVerbosity`. Os callbacks de métrica, avaliação e adaptação apontam para o Service Python.
- As estratégias habilitadas voltaram a variar por cenário: `h` usa réplicas; `hq` usa réplicas e tag; `v` usa CPU; `vq` usa CPU e tag. Os blocos de callback permanecem declarados como nas configurações shell.
- Runtime e API continuam em Pods separados, cada um criado por um `CustomSelfAdapter`. O Service da API continua sendo um manifesto explícito; o operador não foi alterado.
- Para não criar ConfigMap, `runtime.Dockerfile` deriva da imagem genérica existente e copia o profile de cenário para `/config.yaml`. Cada imagem recebe uma tag própria `csa-python-http-runtime:<profile>`.
- Esta implementação ainda tem somente o callback de réplicas. `hq`, `v` e `vq` refletem corretamente a configuração tradicional, mas dependem dos callbacks CPU/tag antes de serem executados. O runtime genérico atual também pode registrar erro quando recebe uma avaliação sem estratégia; nenhum código do runtime externo foi alterado.
- Os manifests removem os mounts e volumes de ConfigMap. A API continua empacotando o mesmo profile na própria imagem para avaliar segundo o cenário escolhido.

### Inspeção, comandos e validação

Foram comparados os quatro profiles HTTP com `autoscalers/csa-go/profiles/` e `autoscalers/csa-java/profiles/`, e lidos os executores HTTP, a avaliação e o fluxo de adaptação do runtime externo em modo somente leitura. Também foram inspecionados os manifests e os Dockerfiles locais. Nenhum build, teste, push de imagem, alteração no runtime externo ou comando no cluster foi executado nesta correção.

Comandos de referência desta revisão:

```sh
rg --files autoscalers/csa-python-http docs/csa-python-http
cat autoscalers/csa-python-http/profiles/{h,hq,v,vq}.yaml
cat autoscalers/csa-go/profiles/{h,hq,v,vq}.yaml
sed -n '1,180p' ../custom-self-adapter/internal/selfadapter/selfadapter.go
sed -n '1,180p' ../custom-self-adapter/internal/evaluatecalc/evaluatecalc.go
git diff --check
```

## Retorno HTTP sem decisão

### Decisão e comportamento

- `/evaluate` retorna `200` com JSON quando seleciona uma estratégia. Sem estratégia aplicável, retorna `204 No Content` sem corpo, como o stdout vazio das versões shell. Isso evita representar ausência de adaptação como `{}`.
- Somente `evaluate.http.successCodes` mudou para `[200, 204]` nos profiles `h`, `hq`, `v` e `vq`. `metric` e `adapt` continuam aceitando apenas `200`.
- O operador e o runtime externo não foram alterados. Nenhuma estratégia noop foi criada.

### Testes e smoke test no cluster

O teste de não decisão falhou com o contrato anterior, passou após a mudança e voltou a falhar quando a produção foi temporariamente revertida. A suíte terminou com `17 passed`:

```sh
.venv/bin/pytest autoscalers/csa-python-http/tests -q
../../.venv/bin/python -c 'import yaml; from pathlib import Path; profiles=[yaml.safe_load(path.read_text()) for path in Path("autoscalers/csa-python-http/profiles").glob("*.yaml")]; assert len(profiles)==4 and all(profile["evaluate"]["http"]["successCodes"] == [200, 204] for profile in profiles)'
```

No cluster `kubernetes-admin@kubernetes`, foram publicados os três apps com tags temporárias `smoke-204-20261014`. No namespace `csa-http-204-smoke-20261014`, um Deployment e Service por API permitiram chamar `/evaluate` via port-forward. Nas três APIs, `940000`/alvo `1000` retornou `204` com corpo vazio; `950000` retornou `200` com `{"strategy":"adapt_replicas","parameters":{"replicas":2}}`. O namespace foi removido ao final. As tags temporárias seguem disponíveis no registry.

Cada build abaixo foi executado no diretório da implementação correspondente em seu worktree. Comandos da publicação e do ciclo temporário no cluster:

```sh
docker build --platform linux/amd64 -t registry.k8s.lab/csa-python-http:smoke-204-20261014 --build-arg PROFILE=h -f Dockerfile .
docker build --platform linux/amd64 -t registry.k8s.lab/csa-znn-go-http:smoke-204-20261014 --build-arg TAG=h -f Dockerfile .
docker build --platform linux/amd64 -t registry.k8s.lab/csa-znn-java-http:smoke-204-20261014 --build-arg PROFILE=h -f Dockerfile .
docker push registry.k8s.lab/csa-python-http:smoke-204-20261014
docker push registry.k8s.lab/csa-znn-go-http:smoke-204-20261014
docker push registry.k8s.lab/csa-znn-java-http:smoke-204-20261014
kubectl create namespace csa-http-204-smoke-20261014
kubectl create deployment csa-python-http --image=registry.k8s.lab/csa-python-http:smoke-204-20261014 --replicas=1 --port=8080 -n csa-http-204-smoke-20261014
kubectl expose deployment csa-python-http --port=8080 --target-port=8080 -n csa-http-204-smoke-20261014
kubectl create deployment csa-go-http --image=registry.k8s.lab/csa-znn-go-http:smoke-204-20261014 --replicas=1 --port=8080 -n csa-http-204-smoke-20261014
kubectl expose deployment csa-go-http --port=8080 --target-port=8080 -n csa-http-204-smoke-20261014
kubectl create deployment csa-java-http --image=registry.k8s.lab/csa-znn-java-http:smoke-204-20261014 --replicas=1 --port=8080 -n csa-http-204-smoke-20261014
kubectl expose deployment csa-java-http --port=8080 --target-port=8080 -n csa-http-204-smoke-20261014
kubectl rollout status deployment/csa-python-http -n csa-http-204-smoke-20261014 --timeout=180s
kubectl rollout status deployment/csa-go-http -n csa-http-204-smoke-20261014 --timeout=180s
kubectl rollout status deployment/csa-java-http -n csa-http-204-smoke-20261014 --timeout=180s
kubectl port-forward service/csa-python-http 18080:8080 -n csa-http-204-smoke-20261014
kubectl port-forward service/csa-go-http 18080:8080 -n csa-http-204-smoke-20261014
kubectl port-forward service/csa-java-http 18080:8080 -n csa-http-204-smoke-20261014
curl -sS -i -X POST http://127.0.0.1:18080/evaluate -H 'Content-Type: application/json' --data-raw '{"resource":{"spec":{"replicas":2}},"metrics":[{"value":"{\"current_value\":\"940000\",\"target_value\":\"1000\"}"}]}'
curl -sS -i -X POST http://127.0.0.1:18080/evaluate -H 'Content-Type: application/json' --data-raw '{"resource":{"spec":{"replicas":2}},"metrics":[{"value":"{\"current_value\":\"950000\",\"target_value\":\"1000\"}"}]}'
kubectl delete namespace csa-http-204-smoke-20261014 --wait=true --timeout=120s
```

O teste cobriu os Services das APIs no cluster. Não executou o ciclo completo do operador/runtime nem alterou o Deployment de pesquisa. Embora o executor HTTP reconheça `204` como sucesso, o runtime genérico ainda tenta interpretar o corpo vazio como JSON e retorna erro antes de chamar a adaptação. O stdout vazio do shell segue o mesmo caminho. Evitar esse erro e tratar a ausência como no-op bem-sucedido exige uma mudança no runtime externo, que permaneceu sem alterações.

O build local disponível para validação posterior é `docker build --build-arg PROFILE=h -t csa-python-http:h autoscalers/csa-python-http`, executado da raiz do repositório. Não foi executado nesta atividade, conforme escopo. Também não foram executados testes, publicação de imagem, aplicação dos manifests, Vagrant ou validação de cluster. A revisão estática confirmou que os manifests usam somente os campos declarados no schema compartilhado e que a regra RBAC se limita à métrica consultada.

## Comparação dos retornos sem decisão no runtime

Em 2026-10-14, foi executada uma sondagem isolada do `evaluatecalc.Evaluator` real do runtime, sem modificar o repositório de referência. O executor HTTP recebeu `204` sem corpo; o executor shell rodou `/bin/sh -c true`, encerrando com sucesso e sem escrever no `stdout`. Ambos chegaram ao parser como string vazia e produziram o mesmo erro: `failed to parse JSON evaluation, got '': unexpected end of JSON input`.

A sondagem usou um teste Go temporário e `go test -overlay`; o arquivo de teste, overlay e cache ficaram em `/tmp/csa-runtime-204-probe`. Comando executado no repositório do runtime:

```sh
GOCACHE=/tmp/csa-runtime-204-probe/gocache GOSUMDB=off GOPROXY=off go test -overlay=/tmp/csa-runtime-204-probe/overlay.json -run TestNoDecisionShellAndHTTPResponsesReachSameParserError -v ./internal/evaluatecalc
```

O teste passou ao confirmar que os dois protocolos falham da mesma forma. Isso mostra que o problema não é exclusivo das APIs HTTP; também há registro de erro para stdout vazio no caminho shell durante carga em [`docs/csa-go/execucao-carga.md`](../csa-go/execucao-carga.md). Nenhum cluster, `run_tests.sh`, arquivo do runtime ou operador foi alterado nesta verificação. A validação prática posterior deve observar esse ciclo no runtime implantado.

## Adaptações de CPU e tag

### Decisões

- Foram adicionados os callbacks `/adapt/cpu` e `/adapt/tag`, cada um ligado a um módulo próprio. `adapt_state.py` compartilha o acesso ao Deployment, Pods, baseline e status `initialData`.
- CPU lê o multiplicador da avaliação, arredonda como a versão shell, limita o resultado entre baseline e `maxCPU` e envia `pods/resize` para os containers `znn` e `nginx`. Rollouts ativos e tags sem próximo passo retornam `{"result":"skip"}`.
- Tag percorre a mesma escada de imagens, grava o tag inicial no CSA, não aumenta acima dele e sincroniza limites de CPU no Deployment quando recebe `update_cpu`.
- A avaliação consulta CPU atual e baseline quando o cenário habilita `adapt_cpu`; isso permite escolher também a redução de CPU em carga baixa.
- Os manifests da API adicionam somente a regra RBAC `pods/resize: patch`. Runtime e API permanecem em Pods separados; não houve alteração no operador, runtime externo, ConfigMap ou `run_tests.sh`.

### Testes e comandos

Os testes de callback falharam quando as rotas não estavam registradas, passaram após a implementação e voltaram a falhar quando as rotas foram temporariamente removidas. Os testes unitários verificam limites de CPU, rollout, payloads de resize, persistência de `initialData`, mudança de tag e sincronização de CPU.

```sh
.venv/bin/python -m pytest autoscalers/csa-python-http/tests -q
git diff --check
```

Resultado final: `27 passed`. Os testes usam clientes simulados; não foram publicadas imagens nem aplicados manifests no cluster.

## Validação funcional dos callbacks no cluster

Em 2026-10-14, a API Python foi publicada com a tag temporária `registry.k8s.lab/csa-python-http:functional-test-9103c55966` e executada como Pod gerenciado pelo operador. O Service `csa-python-http-api` foi aplicado separadamente. A validação usou apenas o CustomSelfAdapter da API, sem criar o Pod do runtime genérico.

O estado inicial observado no cluster era kube-znn com uma réplica, imagem `registry.k8s.lab/znn:600k` e limite de CPU `750m` nos containers znn e nginx. Para exercitar uma redução real de CPU, o initialData do Pod temporário foi semeado com baseline sintético de `600m` e tag `600k`; esse dado foi removido junto com o Pod de teste.

| Chamada no Service | Resultado observado |
| --- | --- |
| `GET /health` | HTTP 200, status UP |
| `POST /metric` com payload de métricas no formato do runtime | HTTP 200, current value `850000`, target `1000` |
| `POST /evaluate` com razão `0.85` | HTTP 200, estratégia `adapt_cpu`, multiplicador `0.85` |
| `POST /adapt/cpu` | resize real de znn/nginx para `638m`; multiplicador `1.2` depois foi limitado a `750m` |
| `POST /adapt/tag` com `tag_up=false` e `update_cpu=true` | imagem `600k → 400k`, rollout concluído e CPU `638m` sincronizada no template |
| `POST /adapt/tag` com `tag_up=true` e `update_cpu=true` | imagem e template restaurados para `600k` e `750m`; rollout concluído |
| `POST /evaluate` com razão `0.94` | HTTP 204 sem corpo na faixa sem decisão |
| `POST /adapt/replicas` | duas réplicas ficaram Ready; em seguida retornou a uma |

Comandos principais executados a partir da raiz do worktree:

```sh
docker build --build-arg PROFILE=vq -t registry.k8s.lab/csa-python-http:functional-test-9103c55966 autoscalers/csa-python-http
docker push registry.k8s.lab/csa-python-http:functional-test-9103c55966
kubectl apply -f autoscalers/csa-python-http/service.yaml
kubectl apply --dry-run=server -f /tmp/csa-python-http-functional-test.yaml
kubectl apply -f /tmp/csa-python-http-functional-test.yaml
kubectl port-forward --address 127.0.0.1 service/csa-python-http-api 18080:8080 -n default
curl -i -H 'Content-Type: application/json' -X POST --data-raw '{"evaluation":{"strategy":"adapt_cpu","parameters":{"cpu_multiplier":0.85}}}' http://127.0.0.1:18080/adapt/cpu
kubectl rollout status deployment/kube-znn -n default --timeout=180s
kubectl delete customselfadapter csa-znn-python-http -n default
kubectl delete service csa-python-http-api -n default
```

O Deployment foi confirmado restaurado ao estado inicial (`1` réplica, `600k`, `750m` nos dois containers). O Service e o CustomSelfAdapter temporários foram excluídos, e o operador removeu o Pod/RBAC correspondente. A métrica externa estava sem amostras (`items: []`); por isso o callback de métrica recebeu entrada sintética no formato do runtime e não foi iniciado um ciclo real do runtime. Nenhum teste de carga nem `run_tests.sh` foi executado.

A imagem local foi removida. A tag temporária permanece no registry interno: a tentativa de excluir o manifest via API do registry respondeu HTTP 500. Não foi alterado o runtime, operador ou outro repositório.
