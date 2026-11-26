-- Aplicações que gravam auditoria (uma por projeto). O código é o filtro usado pelo gerenciador.
create table application (
    code            varchar(40)  primary key check (code ~ '^[a-z0-9][a-z0-9_-]{1,39}$'),
    name            varchar(120) not null,
    active          boolean      not null default true,
    retention_days  integer      not null default 365 check (retention_days between 7 and 3650),
    created_at      timestamp with time zone not null
);

-- Chaves de escrita. Só o hash (SHA-256) é guardado; a chave em texto puro aparece uma única vez, na criação.
create table api_key (
    id                uuid         primary key,
    application_code  varchar(40)  not null references application (code),
    key_prefix        varchar(16)  not null,
    key_hash          bytea        not null unique,
    created_at        timestamp with time zone not null,
    last_used_at      timestamp with time zone,
    revoked_at        timestamp with time zone
);
create index idx_api_key_application on api_key (application_code);

-- Eventos de auditoria, particionados por mês de ocorrência (as partições são criadas pelo serviço).
create table audit_event (
    id                bigint       generated always as identity,
    event_id          uuid         not null,
    application_code  varchar(40)  not null,
    service           varchar(40)  not null,
    action            varchar(60)  not null,
    outcome           varchar(10)  not null check (outcome in ('SUCCESS', 'FAILURE', 'DENIED')),
    actor_id          uuid,
    actor_type        varchar(10)  not null check (actor_type in ('USER', 'ADMIN', 'SYSTEM', 'ANONYMOUS')),
    target_type       varchar(40),
    target_id         varchar(64),
    ip                varchar(64),
    user_agent        varchar(300),
    correlation_id    varchar(64),
    schema_version    integer      not null default 1,
    detail            jsonb,
    occurred_at       timestamp with time zone not null,
    received_at       timestamp with time zone not null,
    primary key (id, occurred_at)
) partition by range (occurred_at);

-- Idempotência: o mesmo evento reenviado (retry da outbox do cliente) não duplica.
create unique index uq_audit_event_event_id on audit_event (event_id, occurred_at);
create index idx_audit_event_app_time on audit_event (application_code, occurred_at desc, id desc);
create index idx_audit_event_app_actor on audit_event (application_code, actor_id, occurred_at desc);
create index idx_audit_event_app_action on audit_event (application_code, action, occurred_at desc);
create index idx_audit_event_correlation on audit_event (correlation_id) where correlation_id is not null;
create index idx_audit_event_detail on audit_event using gin (detail jsonb_path_ops);

-- A própria administração do serviço é auditada sob esta aplicação.
insert into application (code, name, active, retention_days, created_at)
values ('audit-service', 'Serviço de auditoria', true, 730, now());
