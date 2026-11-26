package com.petersonwsf.audit.web;

import com.petersonwsf.audit.domain.EventRepository;
import com.petersonwsf.audit.security.Caller;
import com.petersonwsf.audit.security.Requires;
import com.petersonwsf.audit.security.Role;
import com.petersonwsf.audit.service.IngestService;
import com.petersonwsf.audit.service.QueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/** Ingestão (chave da aplicação) e consulta (token de leitura) de eventos de auditoria. */
@RestController
@RequestMapping("/v1/events")
public class EventController {

    private final IngestService ingest;
    private final QueryService query;
    private final JsonMapper mapper;

    public EventController(IngestService ingest, QueryService query, JsonMapper mapper) {
        this.ingest = ingest;
        this.query = query;
        this.mapper = mapper;
    }

    @PostMapping
    @Requires(Role.WRITE)
    public Dtos.IngestResponse receive(@RequestAttribute(Caller.ATTRIBUTE) Caller caller,
                                       @RequestBody Dtos.IngestRequest request) {
        return Dtos.IngestResponse.of(ingest.ingest(caller.applicationCode(), request.events()));
    }

    @GetMapping
    @Requires(Role.READ)
    public Dtos.EventPage search(@RequestParam String application,
                                 @RequestParam(required = false) String service,
                                 @RequestParam(required = false) String action,
                                 @RequestParam(required = false) String outcome,
                                 @RequestParam(required = false) UUID actor,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                 @RequestParam(required = false) String correlationId,
                                 @RequestParam(required = false) Integer limit,
                                 @RequestParam(required = false) String cursor) {
        Instant cursorAt = null;
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            Object[] decoded = QueryService.decodeCursor(cursor);
            cursorAt = (Instant) decoded[0];
            cursorId = (Long) decoded[1];
        }
        var filter = new EventRepository.Filter(application, blankToNull(service), blankToNull(action),
                blankToNull(outcome), actor, from, to, blankToNull(correlationId), cursorAt, cursorId);
        return Dtos.EventPage.of(query.search(filter, limit), mapper);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
