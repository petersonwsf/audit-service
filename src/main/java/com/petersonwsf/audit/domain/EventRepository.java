package com.petersonwsf.audit.domain;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class EventRepository {

    /** Filtros da consulta; campos nulos não filtram. {@code cursor*} é a posição da página anterior. */
    public record Filter(String application, String service, String action, String outcome, UUID actorId,
                         Instant from, Instant to, String correlationId, Instant cursorOccurredAt, Long cursorId) {
    }

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Grava o evento; devolve {@code false} se o mesmo {@code eventId} já existia (reenvio idempotente). */
    public boolean insert(String applicationCode, NewEvent e, Instant receivedAt) {
        int rows = jdbc.sql("""
                        insert into audit_event (event_id, application_code, service, action, outcome, actor_id, actor_type,
                                                 target_type, target_id, ip, user_agent, correlation_id, schema_version,
                                                 detail, occurred_at, received_at)
                        values (:eventId, :app, :service, :action, :outcome, :actorId, :actorType,
                                :targetType, :targetId, :ip, :userAgent, :correlationId, :schemaVersion,
                                cast(:detail as jsonb), :occurredAt, :receivedAt)
                        on conflict (event_id, occurred_at) do nothing
                        """)
                .param("eventId", e.eventId())
                .param("app", applicationCode)
                .param("service", e.service())
                .param("action", e.action())
                .param("outcome", e.outcome())
                .param("actorId", e.actorId())
                .param("actorType", e.actorType())
                .param("targetType", e.targetType())
                .param("targetId", e.targetId())
                .param("ip", e.ip())
                .param("userAgent", e.userAgent())
                .param("correlationId", e.correlationId())
                .param("schemaVersion", e.schemaVersion())
                .param("detail", e.detailJson())
                .param("occurredAt", Db.ts(e.occurredAt()))
                .param("receivedAt", Db.ts(receivedAt))
                .update();
        return rows > 0;
    }

    /** Eventos do mais novo para o mais antigo (desempate por id); pede {@code limit} linhas. */
    public List<StoredEvent> search(Filter f, int limit) {
        StringBuilder sql = new StringBuilder("select * from audit_event where application_code = :app");
        Map<String, Object> params = new HashMap<>();
        params.put("app", f.application());
        List<String> conditions = new ArrayList<>();
        addEquals(conditions, params, "service", f.service());
        addEquals(conditions, params, "action", f.action());
        addEquals(conditions, params, "outcome", f.outcome());
        addEquals(conditions, params, "actor_id", f.actorId());
        addEquals(conditions, params, "correlation_id", f.correlationId());
        if (f.from() != null) {
            conditions.add("occurred_at >= :from");
            params.put("from", Db.ts(f.from()));
        }
        if (f.to() != null) {
            conditions.add("occurred_at <= :to");
            params.put("to", Db.ts(f.to()));
        }
        if (f.cursorOccurredAt() != null && f.cursorId() != null) {
            conditions.add("(occurred_at, id) < (:cursorAt, :cursorId)");
            params.put("cursorAt", Db.ts(f.cursorOccurredAt()));
            params.put("cursorId", f.cursorId());
        }
        conditions.forEach(c -> sql.append(" and ").append(c));
        sql.append(" order by occurred_at desc, id desc limit :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, n) -> map(rs)).list();
    }

    /** Apaga, em lote, eventos da aplicação mais antigos que {@code before}; devolve quantos foram apagados. */
    public int deleteOlderThan(String applicationCode, Instant before, int batchSize) {
        return jdbc.sql("""
                        delete from audit_event e using (
                            select id, occurred_at from audit_event
                            where application_code = :app and occurred_at < :before
                            limit :batch) d
                        where e.id = d.id and e.occurred_at = d.occurred_at
                        """)
                .param("app", applicationCode)
                .param("before", Db.ts(before))
                .param("batch", batchSize)
                .update();
    }

    /** Nomes das partições de {@code audit_event} (ex.: {@code audit_event_2026_11}). */
    public List<String> partitionNames() {
        return jdbc.sql("""
                        select c.relname from pg_inherits i
                        join pg_class c on c.oid = i.inhrelid
                        join pg_class p on p.oid = i.inhparent
                        where p.relname = 'audit_event' order by c.relname
                        """)
                .query(String.class)
                .list();
    }

    private static void addEquals(List<String> conditions, Map<String, Object> params, String column, Object value) {
        if (value != null) {
            conditions.add(column + " = :" + column);
            params.put(column, value);
        }
    }

    private static StoredEvent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredEvent(rs.getLong("id"), Db.uuid(rs, "event_id"), rs.getString("application_code"),
                rs.getString("service"), rs.getString("action"), rs.getString("outcome"), Db.uuid(rs, "actor_id"),
                rs.getString("actor_type"), rs.getString("target_type"), rs.getString("target_id"), rs.getString("ip"),
                rs.getString("user_agent"), rs.getString("correlation_id"), rs.getInt("schema_version"),
                rs.getString("detail"), Db.instant(rs, "occurred_at"), Db.instant(rs, "received_at"));
    }
}
