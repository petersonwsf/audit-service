package com.petersonwsf.audit.domain;

import java.time.Instant;

/** Aplicação que grava auditoria. {@code code} é o filtro usado na consulta. */
public record Application(String code, String name, boolean active, int retentionDays, Instant createdAt) {
}
