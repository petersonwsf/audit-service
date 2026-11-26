package com.petersonwsf.audit.service;

import com.petersonwsf.audit.domain.ApplicationRepository;
import com.petersonwsf.audit.domain.EventRepository;
import com.petersonwsf.audit.domain.StoredEvent;
import com.petersonwsf.audit.web.ApiException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** Consulta de eventos por aplicação, com filtros e paginação por cursor (do mais novo para o mais antigo). */
@Service
public class QueryService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    private static final Set<String> OUTCOMES = Set.of("SUCCESS", "FAILURE", "DENIED");

    /** Uma página: os eventos e o cursor da próxima (ou {@code null} se acabou). */
    public record Page(List<StoredEvent> items, String nextCursor) {
    }

    private final EventRepository events;
    private final ApplicationRepository applications;

    public QueryService(EventRepository events, ApplicationRepository applications) {
        this.events = events;
        this.applications = applications;
    }

    public Page search(EventRepository.Filter filter, Integer requestedLimit) {
        if (applications.find(filter.application()).isEmpty()) {
            throw ApiException.notFound("aplicacao_nao_encontrada", "Aplicação não cadastrada");
        }
        if (filter.outcome() != null && !OUTCOMES.contains(filter.outcome())) {
            throw ApiException.badRequest("resultado_invalido", "outcome deve ser SUCCESS, FAILURE ou DENIED");
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw ApiException.badRequest("periodo_invalido", "'from' não pode ser posterior a 'to'");
        }
        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        List<StoredEvent> rows = events.search(filter, limit + 1);
        if (rows.size() <= limit) {
            return new Page(rows, null);
        }
        List<StoredEvent> page = rows.subList(0, limit);
        return new Page(page, encodeCursor(page.get(limit - 1)));
    }

    // ------------------------------------------------------------------ cursor opaco: "<micros>.<id>" em base64url

    static String encodeCursor(StoredEvent last) {
        Instant at = last.occurredAt();
        long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((micros + "." + last.id()).getBytes(StandardCharsets.UTF_8));
    }

    /** Devolve {instante, id} do cursor; cursor adulterado vira 400. */
    public static Object[] decodeCursor(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\.", 2);
            long micros = Long.parseLong(parts[0]);
            long id = Long.parseLong(parts[1]);
            return new Object[]{Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    Math.floorMod(micros, 1_000_000L) * 1_000L), id};
        } catch (RuntimeException e) {
            throw ApiException.badRequest("cursor_invalido", "Cursor inválido");
        }
    }
}
