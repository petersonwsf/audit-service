package com.petersonwsf.audit.domain;

import java.time.Instant;
import java.util.UUID;

/** Metadados de uma chave de escrita. O valor da chave nunca é guardado (só o hash). */
public record ApiKey(UUID id, String applicationCode, String prefix, Instant createdAt, Instant lastUsedAt,
                     Instant revokedAt) {

    public boolean active() {
        return revokedAt == null;
    }
}
