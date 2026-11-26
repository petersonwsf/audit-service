# Arquitetura — audit-service

Spring Boot 4 · Java 21. Camadas: `web` (controllers, DTOs, erros) → `service` (regras) → `domain` (registros e repositórios
JDBC). Configuração em `@ConfigurationProperties` (record) alimentada por variáveis de ambiente; erros via `ApiException` +
`GlobalExceptionHandler` com o corpo `{ mensagem, sucesso: false, codigo }`; `Clock` injetado.

```
audit_service/
├── pom.xml                       # Spring Boot 4.1.2, Java 21; testes com PostgreSQL embarcado (sem Docker)
├── Dockerfile                    # build multi-stage; contexto = raiz do repositório; logs JSON; usuário não-root
├── .env.example                  # variáveis para rodar localmente (só nomes)
├── .github/workflows/            # ci.yml (PR: lint → testes) e cd.yml (main: testes → GHCR → deploy na VPS)
├── deploy/                       # docker-compose de produção (serviço + PostgreSQL próprio), .env.example e LEIAME.md
├── docs/ARCHITECTURE.md          # este arquivo
└── src/
    ├── main/resources/
    │   ├── application.yml       # configuração (variáveis AUDIT_*)
    │   └── db/migration/V1__audit.sql   # application, api_key e audit_event (particionada por mês)
    ├── main/java/com/petersonwsf/audit/
    │   ├── AuditServiceApplication.java # inicialização (agendamento e propriedades ligados)
    │   ├── config/               # AuditProperties (security, limits, retention) e ClockConfig
    │   ├── logging/              # CorrelationId (MDC) e CorrelationIdFilter (id por requisição + linha de acesso)
    │   ├── security/             # Role, @Requires, Caller, AuthInterceptor (chave/tokens), RateLimiter, WebConfig
    │   ├── web/                  # EventController (ingestão e consulta), ApplicationController (aplicações e chaves), Dtos, erros
    │   ├── service/              # IngestService, EventValidator, DetailGuard, QueryService, ApplicationService,
    │   │                         # ApiKeyService, PartitionService, MaintenanceJobs
    │   └── domain/               # Application, ApiKey, NewEvent, StoredEvent e os repositórios (JdbcClient)
    └── test/java/com/petersonwsf/audit/
        ├── support/              # AbstractAuditTest (PostgreSQL embarcado) e Http (cliente do JDK)
        └── *Test.java            # ingestão, consulta, administração, manutenção, limite de autenticação, DetailGuard
```

## Como funciona

- **Autenticação por papel.** `@Requires(Role.X)` nas rotas. `WRITE`: chave da aplicação (hash SHA-256 no banco; o código da
  aplicação vem da chave). `READ` e `ADMIN`: tokens do ambiente comparados em tempo constante. Rota sem anotação exige `ADMIN`.
  O serviço recusa subir sem os dois tokens (≥ 32 caracteres e diferentes).
- **Ingestão.** `IngestService` valida cada evento (`EventValidator`) e recusa só os inválidos; garante as partições dos meses
  envolvidos (`PartitionService`, em transação própria) e grava com `ON CONFLICT (event_id, occurred_at) DO NOTHING`
  (idempotente). `DetailGuard` barra chaves de dado pessoal no `detail`.
- **Consulta.** `QueryService` pagina por cursor (`occurred_at desc, id desc`); o cursor é opaco (base64url).
- **Administração.** `ApplicationService` cadastra aplicações e chaves (até 2 ativas) e audita cada mudança sob a aplicação
  `audit-service`. O valor da chave só existe na resposta da criação.
- **Manutenção.** `MaintenanceJobs` (agendado, com escopo de correlation id próprio): cria as partições dos próximos meses,
  apaga em lotes os eventos fora da retenção de cada aplicação e remove partições mais antigas que a maior retenção.

## Banco

PostgreSQL próprio. `audit_event` é particionada por mês (chave primária e índice único incluem `occurred_at`); índices por
`(application_code, occurred_at)`, ator, ação, correlação e GIN em `detail`. Mudanças de schema = **nova migration**.
