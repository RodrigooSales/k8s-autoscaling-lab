# CSA Java HTTP

Implementação independente do CSA em Java, organizada como um serviço HTTP de longa duração. Ela terá seu próprio ciclo de coleta, avaliação e adaptação; não reutilizará o runtime genérico que executa etapas por shell.

O objetivo de paridade é manter os efeitos observáveis das adaptações equivalentes às demais implementações: réplicas, CPU, tag da imagem, baseline e comportamento durante rollouts.

## Base do projeto

- Java 21 e Gradle Wrapper 9.3.
- Spring Boot 4.1.1 com Spring MVC para HTTP e Actuator para health checks.
- Kubernetes Java Client 26.0.0, alinhado ao CSA Java existente.
- A política de avaliação reproduz as 10 fixtures compartilhadas.
- `MetricCollectionService` concentra a configuração da coleta e as consultas ao Deployment e à métrica externa. Soma todas as séries retornadas, converte `Quantity` para milésimos e falha com erro explícito quando a lista vem vazia. O resultado é um `MetricSnapshot` definido como record dentro do próprio serviço; `MetricCollectionException` permanece separado.
- `ReplicaAdaptationService` aplica a quantidade decidida ao campo `Deployment.spec.replicas` por strategic merge patch. Falhas da API são propagadas como `AdaptationException`.
- Um único `ApiClient` lazy é criado pela aplicação e compartilhado pela coleta e pela adaptação de réplicas. Essas operações ainda não estão ligadas a um ciclo periódico; adaptação de CPU e de tag ainda não foi implementada.
- O health endpoint do Actuator continua disponível sem exigir acesso ao cluster na inicialização.

Os valores atuais de namespace, Deployment, métrica, seletor e alvo estão em `src/main/resources/application.yaml` e são injetados diretamente no serviço.

## Empacotamento para testes futuros

- `Dockerfile` constrói o `bootJar` e executa apenas o serviço Java na porta `8080`. O argumento `PROFILE` escolhe o YAML que será copiado para `/app/config/application.yaml`; Spring Boot o carrega com `spring.config.additional-location`. O padrão de build é `h`; o profile selecionado deve corresponder ao sufixo do manifesto. Para outro cenário, use, por exemplo, `docker build --build-arg PROFILE=hq ...`.
- `profiles/{h,hq,v,vq}.yaml` descrevem os parâmetros CSA e os conjuntos de estratégias planejados. Os serviços atuais leem namespace, Deployment, métrica, seletor e alvo. `interval`, limites de réplicas/CPU e `enabled-strategies` ainda não estão conectados a um ciclo de execução e avaliação. Somente `h` corresponde à adaptação implementada até agora. `adapt_cpu` e `adapt_tag` nos demais profiles são presets futuros, sem adaptadores Java HTTP correspondentes.
- `custom-selfadapter-{h,hq,v,vq}.yaml` são recursos `CustomSelfAdapter` para provisionar o Pod do serviço usando a CRD compartilhada do operador em `charts/custom-self-adapter-operator/templates/crd/`.
- O operador injeta sua conta de serviço. A regra adicional permite apenas `get` e `list` para a métrica externa `znn_latency_ms_p95`; o Role padrão do operador já permite a leitura e o patch de Deployments.
- Readiness e liveness usam os grupos de health do Actuator. Não existe ainda ciclo periódico nem endpoint de coleta, avaliação ou adaptação; os recursos não executam o autoscaling de ponta a ponta.

## Desenvolvimento local

- Inicie a aplicação com `./gradlew bootRun`.
- Consulte `http://localhost:8080/actuator/health` para verificar a saúde do serviço.
- Execute os testes de contrato e da coleta HTTP com `./gradlew test`.

O histórico de decisões, comandos e validações fica em [docs/csa-java-http/historico-de-desenvolvimento.md](../../docs/csa-java-http/historico-de-desenvolvimento.md).
