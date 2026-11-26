package com.petersonwsf.audit.web;

import com.petersonwsf.audit.domain.ApiKey;
import com.petersonwsf.audit.domain.Application;
import com.petersonwsf.audit.domain.StoredEvent;
import com.petersonwsf.audit.service.ApplicationService;
import com.petersonwsf.audit.service.IngestService;
import com.petersonwsf.audit.service.QueryService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Corpos de entrada e saída da API pública. */
public final class Dtos {

    private Dtos() {
    }

    // ------------------------------------------------------------------ ingestão

    /** Lote de eventos; cada elemento é validado individualmente pelo serviço. */
    public record IngestRequest(List<JsonNode> events) {
    }

    public record IngestResponse(int accepted, int duplicates, List<IngestService.Rejection> rejected) {

        static IngestResponse of(IngestService.Outcome outcome) {
            return new IngestResponse(outcome.accepted(), outcome.duplicates(), outcome.rejected());
        }
    }

    // ------------------------------------------------------------------ consulta

    public record EventView(long id, UUID eventId, String application, String service, String action, String outcome,
                            UUID actorId, String actorType, String targetType, String targetId, String ip,
                            String userAgent, String correlationId, int schemaVersion, JsonNode detail,
                            Instant occurredAt, Instant receivedAt) {

        static EventView of(StoredEvent e, JsonMapper mapper) {
            JsonNode detail = e.detailJson() == null ? null : mapper.readTree(e.detailJson());
            return new EventView(e.id(), e.eventId(), e.applicationCode(), e.service(), e.action(), e.outcome(),
                    e.actorId(), e.actorType(), e.targetType(), e.targetId(), e.ip(), e.userAgent(), e.correlationId(),
                    e.schemaVersion(), detail, e.occurredAt(), e.receivedAt());
        }
    }

    public record EventPage(List<EventView> items, String nextCursor) {

        static EventPage of(QueryService.Page page, JsonMapper mapper) {
            return new EventPage(page.items().stream().map(e -> EventView.of(e, mapper)).toList(), page.nextCursor());
        }
    }

    // ------------------------------------------------------------------ aplicações e chaves

    public record ApplicationView(String code, String name, boolean active, int retentionDays, Instant createdAt) {

        static ApplicationView of(Application a) {
            return new ApplicationView(a.code(), a.name(), a.active(), a.retentionDays(), a.createdAt());
        }
    }

    /** Versão enxuta para quem só lê (gerenciador): sem retenção nem metadados. */
    public record ApplicationSummary(String code, String name, boolean active) {

        static ApplicationSummary of(Application a) {
            return new ApplicationSummary(a.code(), a.name(), a.active());
        }
    }

    public record CreateApplicationRequest(String code, String name, Integer retentionDays) {
    }

    public record UpdateApplicationRequest(String name, Boolean active, Integer retentionDays) {
    }

    public record KeyView(UUID id, String prefix, boolean active, Instant createdAt, Instant lastUsedAt,
                          Instant revokedAt) {

        static KeyView of(ApiKey k) {
            return new KeyView(k.id(), k.prefix(), k.active(), k.createdAt(), k.lastUsedAt(), k.revokedAt());
        }
    }

    /** Resposta da criação de chave: o valor em {@code key} aparece UMA vez e não pode ser consultado depois. */
    public record CreatedKeyView(UUID id, String application, String prefix, String key, Instant createdAt) {

        static CreatedKeyView of(ApplicationService.CreatedKey created) {
            return new CreatedKeyView(created.key().id(), created.key().applicationCode(), created.key().prefix(),
                    created.plainKey(), created.key().createdAt());
        }
    }
}
