# CSA Go

Implementação em Go do Custom Self Adapter, baseada nas implementações
[Python](../csa/) e [Java](../csa-java/).

O executável aceita uma etapa por processo. `metric` lê uma especificação JSON pelo stdin,
extrai os campos da primeira métrica e escreve o resultado no stdout; não lê configuração
nem inicia clientes Kubernetes. `evaluate` lê `/config.yaml`, acessa o Kubernetes usando
autenticação in-cluster, calcula a estratégia e persiste o CPU inicial na anotação do Pod
CSA quando necessário. `adapt_replicas` lê o resultado da avaliação, obtém o Deployment e
envia o objeto atualizado por strategic merge patch. `adapt_cpu` redimensiona os Pods por
`pods/resize`; `adapt_tag` percorre a escada de imagens e pode sincronizar os limites de CPU
no Deployment.

## Dependências

- `k8s.io/client-go`: cliente Kubernetes e autenticação in-cluster.
- `k8s.io/api`: tipos de recursos, como Deployments e Pods.
- `k8s.io/apimachinery`: metadados, patches e quantidades de CPU/memória.
- `go.yaml.in/yaml/v2`: leitura da configuração YAML, equivalente a PyYAML/SnakeYAML.
- `sigs.k8s.io/yaml`: dependência indireta mantida pelos módulos Kubernetes.

Os módulos Kubernetes usam `v0.35.3`, alinhada ao Kubernetes 1.35.3 do laboratório.
O parser YAML de configuração usa `v2.4.3`.

A comunicação JSON por stdin/stdout com o operador usa `encoding/json` da
biblioteca padrão, equivalente ao `json` do Python e ao Gson do Java.
O parsing atual da CLI, a escrita de logs e os testes também usam a biblioteca padrão,
incluindo o pacote `testing`.

Configuração, métrica, avaliação, persistência de estado, os três adaptadores e os clientes
Kubernetes têm testes unitários independentes de cluster e rede externa.

## Desenvolvimento

Go 1.25, conforme o `mise.toml`. Execute nesta pasta:

```bash
printf '%s' '{"resource":{"spec":{"replicas":1}},"kubernetesMetrics":[{"spec":{"external":{"target":{"value":"1000"}}},"external":{"current":{"value":"950"}}}]}' | mise exec -- go run ./cmd/csa-go -m metric
mise exec -- go build -o bin/csa-go ./cmd/csa-go
mise exec -- go test ./...
```

Os testes unitários leem os contratos de `../contracts/cases` e isolam stdin, stdout,
stderr e o arquivo de log. Quando o cache Go padrão não puder ser gravado, defina
`GOCACHE` para um diretório gravável, por exemplo `/tmp/csa-go-gocache`.

## Imagens e manifests

`Dockerfile` usa a imagem runtime `registry.k8s.lab/custom-self-adapter:alpine-latest`,
fixada em digest, e preserva seu comando `/app/custom-self-adapter`. O script compila um
binário estático `linux/amd64`, seleciona um dos perfis e publica a imagem em
`registry.k8s.lab/csa-znn-go`. Requer Go 1.25 via mise, Docker com acesso ao registry interno
e `envsubst`.

```bash
TAG=vq ./build_csa-go.sh
kubectl apply -f custom-selfadapter-vq.yaml
```

Use `TAG=h`, `hq`, `v` ou `vq`; o build gera o manifesto correspondente. Os quatro perfis
mantêm as configurações do CSA Python e usam provisoriamente timeout de 1000 ms para métrica
e 2000 ms para avaliação e adaptações. Os digests publicados e a validação de integração
estão em [validacao-funcional.md](docs/validacao-funcional.md).

## Referências para implementação

O CSA Java reúne em um executável as etapas que o CSA Python implementa em scripts. Go
preserva os contratos JSON por stdin/stdout de `metric`, `evaluate`, `adapt_replicas`,
`adapt_cpu` e `adapt_tag`, além da persistência `initialData`.

Os detalhes das regras preservadas, decisões, comandos e validações estão em `docs/`.

## Estrutura do código

- `cmd/csa-go/`: ponto de entrada do executável.
- `internal/csa/`: modos, configuração, logs e testes, restritos ao módulo.
- `docs/`: decisões, investigação e comandos registrados.
