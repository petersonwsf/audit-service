# audit-service

Serviço **central de auditoria**: várias aplicações gravam nele os eventos de auditoria (quem fez o quê, em quê, quando, de
onde e com que resultado) e um gerenciador consulta tudo num só lugar, filtrando por **aplicação** (`application_code`).
Spring Boot 4 · Java 21 · PostgreSQL 17 (banco próprio) · Flyway.

- **Escrita:** cada aplicação tem uma **chave** própria; a chave fixa o código da aplicação (uma aplicação não grava em nome de outra).
- **Leitura e administração:** tokens do ambiente, separados.
- **Entrega assíncrona:** as aplicações guardam o evento numa outbox local (mesma transação da ação) e um relay o envia em
  lote a cada poucos segundos. O serviço é idempotente por `eventId`, então reenvios não duplicam.
- Documentação da estrutura: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) · deploy: [`deploy/LEIAME.md`](deploy/LEIAME.md).

## Rodando

Precisa de um PostgreSQL (use Docker) e dos dois tokens (≥ 32 caracteres, diferentes). Veja `.env.example`.

```
docker run -d --name audit-pg -e POSTGRES_DB=audit -e POSTGRES_USER=audit -e POSTGRES_PASSWORD=audit -p 5433:5432 postgres:17-alpine
export AUDIT_DB_PASSWORD=audit AUDIT_READ_TOKEN=$(openssl rand -base64 48) AUDIT_ADMIN_TOKEN=$(openssl rand -base64 48)
mvn spring-boot:run          # porta 8090
mvn test                     # testes de integração com PostgreSQL embarcado (sem Docker)
```

## API (`/v1`)

Erros: `{ "mensagem": "...", "sucesso": false, "codigo": "..." }` com `codigo` estável.
Toda resposta traz `X-Correlation-Id` (aceita um recebido, se válido).

| Método | Rota | Credencial | O que faz |
|---|---|---|---|
| POST | `/v1/events` | chave da aplicação | grava um lote (até 100 eventos) |
| GET | `/v1/events` | token de leitura | consulta eventos de uma aplicação |
| GET | `/v1/applications` | token de leitura | lista resumida (`code`, `name`, `active`) |
| GET | `/v1/admin/applications` | token de admin | lista completa |
| POST | `/v1/admin/applications` | token de admin | cadastra `{ code, name, retentionDays? }` |
| PATCH | `/v1/admin/applications/{code}` | token de admin | altera `name`, `active`, `retentionDays` |
| GET | `/v1/admin/applications/{code}/keys` | token de admin | chaves (só metadados) |
| POST | `/v1/admin/applications/{code}/keys` | token de admin | cria uma chave (**o valor aparece uma única vez**) |
| DELETE | `/v1/admin/applications/{code}/keys/{id}` | token de admin | revoga a chave |
| GET | `/actuator/health` | — | saúde (liveness/readiness) |

A credencial vai em `Authorization: Bearer <valor>`.

### `POST /v1/events`

```json
{ "events": [ {
    "eventId": "0191...uuid",          // obrigatório, único: é a chave de idempotência
    "occurredAt": "2026-11-26T17:00:00Z", // obrigatório, ISO-8601 com fuso (até 180 dias no passado, 5 min no futuro)
    "service": "auth",                 // obrigatório: [a-z0-9_-], 2–40 (qual serviço da aplicação gerou)
    "action": "LOGIN_FAILURE",         // obrigatório: [A-Z0-9_.], 2–60
    "outcome": "SUCCESS|FAILURE|DENIED", // obrigatório
    "actorId": "uuid",                 // opcional
    "actorType": "USER|ADMIN|SYSTEM|ANONYMOUS", // opcional (USER se há actorId, senão ANONYMOUS)
    "targetType": "POST", "targetId": "uuid", // opcionais (o que foi afetado)
    "ip": "...", "userAgent": "...",   // opcionais (user agent é cortado em 300)
    "correlationId": "...",            // opcional: liga ao fluxo nos logs (Grafana)
    "schemaVersion": 1,                // opcional (padrão 1)
    "detail": { }                      // opcional: objeto JSON, até 4 KB, sem chaves de dado pessoal
} ] }
```

Resposta `200`: `{ "accepted": n, "duplicates": n, "rejected": [ { "eventId": "...", "reason": "..." } ] }`.
Um evento inválido é recusado **sozinho** (o cliente o marca como falho e não reenvia); o lote não cai por causa dele.
O `application` nunca vem do corpo: é o da chave. Lote vazio → `400 lote_vazio`; mais de 100 → `413 lote_grande_demais`.

**Proteção de dado pessoal:** `detail` é recusado se tiver chave que contenha `password`, `senha`, `secret`, `token`,
`cookie`, `authorization`, `email`, `phone`, `telefone`, `cpf`, `birth` ou `nascimento` (em qualquer nível). Registre ids
(UUID), nunca e-mail, senha, conteúdo de postagens ou corpo de requisição.

### `GET /v1/events`

Parâmetros: `application` (**obrigatório**), `service`, `action`, `outcome`, `actor` (UUID), `from`/`to` (ISO-8601),
`correlationId`, `limit` (1–100, padrão 50), `cursor`. Ordem: do mais novo para o mais antigo.
Resposta: `{ "items": [ { id, eventId, application, service, action, outcome, actorId, actorType, targetType, targetId, ip,
userAgent, correlationId, schemaVersion, detail, occurredAt, receivedAt } ], "nextCursor": "..." | null }`.
Use o `nextCursor` no parâmetro `cursor` para a próxima página. Aplicação desconhecida → `404 aplicacao_nao_encontrada`.

### Chaves

Formato `ak_<43 caracteres>`. Só o hash SHA-256 é guardado; o valor é mostrado **uma vez**, na criação. Cada aplicação pode ter
até **2 chaves ativas** (rotação sem parar nada: crie a nova, troque no `.env`, faça o deploy e revogue a antiga). O
`lastUsedAt` ajuda a achar chaves esquecidas. A própria administração (criar/alterar aplicação, criar/revogar chave) é
auditada sob a aplicação `audit-service`.

## Configuração (variáveis de ambiente)

| Variável | Padrão | Descrição |
|---|---|---|
| `AUDIT_DB_URL` / `AUDIT_DB_USER` / `AUDIT_DB_PASSWORD` | `jdbc:postgresql://localhost:5433/audit` / `audit` / — | banco próprio |
| `AUDIT_READ_TOKEN` | — (**obrigatória**) | token de leitura, ≥ 32 caracteres |
| `AUDIT_ADMIN_TOKEN` | — (**obrigatória**) | token de administração, ≥ 32 caracteres e diferente do de leitura |
| `AUDIT_FAILED_AUTH_PER_MINUTE` | `30` | falhas de autenticação por IP/minuto antes do `429` |
| `AUDIT_MAX_BATCH` | `100` | eventos por lote |
| `AUDIT_MAX_DETAIL_BYTES` | `4096` | tamanho máximo do `detail` |
| `AUDIT_REQUESTS_PER_MINUTE_PER_KEY` | `1200` | limite de requisições por chave |
| `AUDIT_MAX_PAST_AGE` / `AUDIT_MAX_FUTURE_SKEW` | `P180D` / `PT5M` | janela aceita para `occurredAt` |
| `AUDIT_RETENTION_ENABLED` / `AUDIT_RETENTION_INTERVAL` | `true` / `PT6H` | rotina de manutenção |
| `AUDIT_RETENTION_DEFAULT_DAYS` | `365` | retenção das aplicações novas |
| `AUDIT_PARTITIONS_AHEAD` | `2` | meses de partição criados à frente |
| `AUDIT_DELETE_BATCH_SIZE` | `2000` | lote do apagamento por retenção |
| `SERVER_PORT` / `LOG_LEVEL` | `8090` / `INFO` | |

## Banco

Migrations Flyway em `src/main/resources/db/migration/` (**nunca edite uma existente**; mudanças vão em uma nova).
`audit_event` é **particionada por mês** (`occurred_at`); as partições são criadas sob demanda e antecipadas pela rotina.
A retenção é por aplicação (`retention_days`): a rotina apaga em lotes os eventos vencidos e remove partições inteiras que
passaram da maior retenção. Os dados vivem num volume nomeado: nunca rode `docker compose down -v`.

## Segurança e operação

- O serviço **não fica exposto à internet**: só responde na rede Docker interna (`audit-net`). Não publique a porta no host.
- Falhas de autenticação por IP são limitadas (`429`); cada chave tem limite de requisições por minuto.
- Logs em JSON no stdout (Alloy → Loki → Grafana), com `correlationId`. Nunca registram chave, token, IP, user agent ou `detail`.
- Faça backup do banco `audit` fora da VPS.
