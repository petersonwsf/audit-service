package com.petersonwsf.audit.service;

import com.petersonwsf.audit.config.AuditProperties;
import com.petersonwsf.audit.domain.EventRepository;
import com.petersonwsf.audit.domain.NewEvent;
import com.petersonwsf.audit.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Recebe um lote de eventos de uma aplicação: valida cada um, garante as partições e grava (idempotente por eventId). */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** Evento recusado e o motivo (o cliente o marca como falho e não reenvia). */
    public record Rejection(String eventId, String reason) {
    }

    public record Outcome(int accepted, int duplicates, List<Rejection> rejected) {
    }

    private final EventValidator validator;
    private final PartitionService partitions;
    private final EventRepository events;
    private final AuditProperties properties;
    private final Clock clock;

    public IngestService(EventValidator validator, PartitionService partitions, EventRepository events,
                         AuditProperties properties, Clock clock) {
        this.validator = validator;
        this.partitions = partitions;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** {@code applicationCode} vem da chave autenticada; qualquer código dentro do corpo é ignorado. */
    @Transactional
    public Outcome ingest(String applicationCode, List<JsonNode> batch) {
        if (batch == null || batch.isEmpty()) {
            throw ApiException.badRequest("lote_vazio", "Envie ao menos um evento em 'events'");
        }
        if (batch.size() > properties.limits().maxBatch()) {
            throw ApiException.tooLarge("lote_grande_demais",
                    "O lote excede " + properties.limits().maxBatch() + " eventos");
        }
        List<Rejection> rejected = new ArrayList<>();
        List<NewEvent> valid = new ArrayList<>();
        for (JsonNode node : batch) {
            EventValidator.Result result = validator.validate(node);
            if (result.valid()) {
                valid.add(result.event());
            } else {
                rejected.add(new Rejection(eventIdOf(node), result.reason()));
            }
        }
        ensurePartitions(valid);
        int[] counts = store(applicationCode, valid);
        log.info("Lote da aplicação {}: {} aceitos, {} duplicados, {} recusados",
                applicationCode, counts[0], counts[1], rejected.size());
        if (!rejected.isEmpty()) {
            log.warn("Aplicação {} enviou {} evento(s) inválido(s) (ex.: {})",
                    applicationCode, rejected.size(), rejected.get(0).reason());
        }
        return new Outcome(counts[0], counts[1], rejected);
    }

    private void ensurePartitions(List<NewEvent> valid) {
        Set<YearMonth> months = new LinkedHashSet<>();
        valid.forEach(e -> months.add(PartitionService.monthOf(e.occurredAt())));
        months.forEach(partitions::ensure);
    }

    private int[] store(String applicationCode, List<NewEvent> valid) {
        int accepted = 0;
        int duplicates = 0;
        var receivedAt = clock.instant();
        for (NewEvent event : valid) {
            if (events.insert(applicationCode, event, receivedAt)) {
                accepted++;
            } else {
                duplicates++;
            }
        }
        return new int[]{accepted, duplicates};
    }

    private static String eventIdOf(JsonNode node) {
        if (node != null && node.path("eventId").isString()) {
            String raw = node.path("eventId").asString();
            try {
                return UUID.fromString(raw).toString();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
