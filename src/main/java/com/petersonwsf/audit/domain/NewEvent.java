package com.petersonwsf.audit.domain;

import java.time.Instant;
import java.util.UUID;

/** Evento já validado, pronto para ser gravado. {@code detailJson} é um objeto JSON serializado (ou {@code null}). */
public record NewEvent(UUID eventId, Instant occurredAt, String service, String action, String outcome, UUID actorId,
                       String actorType, String targetType, String targetId, String ip, String userAgent,
                       String correlationId, int schemaVersion, String detailJson) {
}
