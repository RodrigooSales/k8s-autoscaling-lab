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

O build local disponível para validação posterior é `docker build --build-arg PROFILE=h -t csa-python-http:h autoscalers/csa-python-http`, executado da raiz do repositório. Não foi executado nesta atividade, conforme escopo. Também não foram executados testes, publicação de imagem, aplicação dos manifests, Vagrant ou validação de cluster. A revisão estática confirmou que os manifests usam somente os campos declarados no schema compartilhado e que a regra RBAC se limita à métrica consultada.

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
