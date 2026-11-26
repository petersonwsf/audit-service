package com.petersonwsf.audit.service;

import com.petersonwsf.audit.config.AuditProperties;
import com.petersonwsf.audit.domain.NewEvent;
import com.petersonwsf.audit.logging.CorrelationId;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Valida um evento recebido (JSON) campo a campo. Um evento inválido é recusado sozinho, sem derrubar o lote. */
@Component
public class EventValidator {

    /** Resultado: o evento válido ou o motivo da recusa. */
    public record Result(NewEvent event, String reason) {

        static Result ok(NewEvent event) {
            return new Result(event, null);
        }

        static Result rejected(String reason) {
            return new Result(null, reason);
        }

        public boolean valid() {
            return event != null;
        }
    }

    private static final Pattern SERVICE = Pattern.compile("[a-z0-9][a-z0-9_-]{1,39}");
    private static final Pattern ACTION = Pattern.compile("[A-Z0-9][A-Z0-9_.]{1,59}");
    private static final Pattern TARGET_TYPE = Pattern.compile("[A-Za-z0-9_.-]{1,40}");
    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE", "DENIED");
    private static final Set<String> ACTOR_TYPES = Set.of("USER", "ADMIN", "SYSTEM", "ANONYMOUS");
    private static final int MAX_TARGET_ID = 64;
    private static final int MAX_IP = 64;
    private static final int MAX_USER_AGENT = 300;

    /** Erro de validação interno (vira o motivo da recusa). */
    private static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    private final AuditProperties properties;
    private final DetailGuard detailGuard;
    private final JsonMapper mapper;
    private final Clock clock;

    public EventValidator(AuditProperties properties, DetailGuard detailGuard, JsonMapper mapper, Clock clock) {
        this.properties = properties;
        this.detailGuard = detailGuard;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Result validate(JsonNode node) {
        try {
            return Result.ok(parse(node));
        } catch (Invalid e) {
            return Result.rejected(e.getMessage());
        }
    }

    private NewEvent parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new Invalid("o evento deve ser um objeto JSON");
        }
        UUID eventId = uuid(node, "eventId", true);
        Instant occurredAt = occurredAt(node);
        String service = pattern(node, "service", SERVICE, true);
        String action = pattern(node, "action", ACTION, true);
        String outcome = oneOf(node, "outcome", OUTCOMES, true);
        UUID actorId = uuid(node, "actorId", false);
        String actorType = oneOf(node, "actorType", ACTOR_TYPES, false);
        if (actorType == null) {
            actorType = actorId != null ? "USER" : "ANONYMOUS";
        }
        return new NewEvent(eventId, occurredAt, service, action, outcome, actorId, actorType,
                pattern(node, "targetType", TARGET_TYPE, false),
                limited(node, "targetId", MAX_TARGET_ID, false),
                limited(node, "ip", MAX_IP, false),
                truncated(node, "userAgent", MAX_USER_AGENT),
                correlationId(node),
                schemaVersion(node),
                detail(node));
    }

    private Instant occurredAt(JsonNode node) {
        String raw = text(node, "occurredAt", true);
        Instant value;
        try {
            value = OffsetDateTime.parse(raw).toInstant();
        } catch (RuntimeException e) {
            throw new Invalid("occurredAt deve ser uma data ISO-8601 com fuso (ex.: 2026-01-01T12:00:00Z)");
        }
        Instant now = clock.instant();
        if (value.isAfter(now.plus(properties.limits().maxFutureSkew()))) {
            throw new Invalid("occurredAt está no futuro");
        }
        if (value.isBefore(now.minus(properties.limits().maxPastAge()))) {
            throw new Invalid("occurredAt é antigo demais para ser aceito");
        }
        return value;
    }

    private String correlationId(JsonNode node) {
        String value = text(node, "correlationId", false);
        return CorrelationId.isValid(value) ? value : null;
    }

    private int schemaVersion(JsonNode node) {
        JsonNode value = node.path("schemaVersion");
        if (value.isMissingNode() || value.isNull()) {
            return 1;
        }
        if (!value.isInt() || value.intValue() < 1 || value.intValue() > 1000) {
            throw new Invalid("schemaVersion deve ser um inteiro entre 1 e 1000");
        }
        return value.intValue();
    }

    private String detail(JsonNode node) {
        JsonNode detail = node.path("detail");
        if (detail.isMissingNode() || detail.isNull()) {
            return null;
        }
        if (!detail.isObject()) {
            throw new Invalid("detail deve ser um objeto JSON");
        }
        detailGuard.violation(detail).ifPresent(reason -> {
            throw new Invalid(reason);
        });
        String json = mapper.writeValueAsString(detail);
        if (json.getBytes(StandardCharsets.UTF_8).length > properties.limits().maxDetailBytes()) {
            throw new Invalid("detail excede " + properties.limits().maxDetailBytes() + " bytes");
        }
        return json;
    }

    // ------------------------------------------------------------------ campos simples

    private static String text(JsonNode node, String field, boolean required) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            if (required) {
                throw new Invalid(field + " é obrigatório");
            }
            return null;
        }
        if (!value.isString()) {
            throw new Invalid(field + " deve ser um texto");
        }
        String text = value.asString();
        return text.isBlank() && !required ? null : text;
    }

    private static UUID uuid(JsonNode node, String field, boolean required) {
        String text = text(node, field, required);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new Invalid(field + " deve ser um UUID");
        }
    }

    private static String pattern(JsonNode node, String field, Pattern pattern, boolean required) {
        String text = text(node, field, required);
        if (text != null && !pattern.matcher(text).matches()) {
            throw new Invalid(field + " em formato inválido");
        }
        return text;
    }

    private static String oneOf(JsonNode node, String field, Set<String> allowed, boolean required) {
        String text = text(node, field, required);
        if (text != null && !allowed.contains(text)) {
            throw new Invalid(field + " deve ser um de " + allowed);
        }
        return text;
    }

    private static String limited(JsonNode node, String field, int max, boolean required) {
        String text = text(node, field, required);
        if (text != null && (text.length() > max || hasControlChars(text))) {
            throw new Invalid(field + " inválido (até " + max + " caracteres, sem controle)");
        }
        return text;
    }

    /** User-agent é só informativo: o excesso é cortado em vez de recusar o evento. */
    private static String truncated(JsonNode node, String field, int max) {
        String text = text(node, field, false);
        if (text == null) {
            return null;
        }
        String clean = text.replaceAll("\\p{Cntrl}", " ");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static boolean hasControlChars(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }
}
