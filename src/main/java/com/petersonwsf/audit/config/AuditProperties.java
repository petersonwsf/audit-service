package com.petersonwsf.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuração tipada do serviço (prefixo {@code audit}), alimentada por variáveis de ambiente. */
@ConfigurationProperties(prefix = "audit")
public record AuditProperties(Security security, Limits limits, Retention retention) {

    public record Security(String readToken, String adminToken, int failedAuthPerMinute) {
    }

    public record Limits(int maxBatch, int maxDetailBytes, int requestsPerMinutePerKey,
                         Duration maxPastAge, Duration maxFutureSkew) {
    }

    public record Retention(boolean enabled, Duration interval, int defaultDays, int partitionsAhead,
                            int deleteBatchSize) {
    }
}
