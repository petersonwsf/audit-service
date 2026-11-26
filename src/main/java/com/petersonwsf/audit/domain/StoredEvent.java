package com.petersonwsf.audit.domain;

import java.time.Instant;
import java.util.UUID;

/** Evento como gravado (resultado de consulta). {@code detailJson} é o jsonb em texto. */
public record StoredEvent(long id, UUID eventId, String applicationCode, String service, String action, String outcome,
                          UUID actorId, String actorType, String targetType, String targetId, String ip,
                          String userAgent, String correlationId, int schemaVersion, String detailJson,
                          Instant occurredAt, Instant receivedAt) {
}
