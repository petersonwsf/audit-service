package com.petersonwsf.audit.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Base dos testes de integração: sobe o serviço completo em porta aleatória contra um PostgreSQL REAL embutido (sem Docker),
 * com as migrations do Flyway e as credenciais de teste.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "audit.retention.enabled=false",
                "audit.security.read-token=" + AbstractAuditTest.READ_TOKEN,
                "audit.security.admin-token=" + AbstractAuditTest.ADMIN_TOKEN,
                "audit.security.failed-auth-per-minute=1000"
        })
public abstract class AbstractAuditTest {

    public static final String READ_TOKEN = "teste-leitura-0123456789-0123456789-abcdef";
    public static final String ADMIN_TOKEN = "teste-admin-0123456789-0123456789-abcdef";

    protected static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao iniciar o PostgreSQL de teste", e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Value("${local.server.port}")
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    private Http http;

    protected Http http() {
        if (http == null) {
            http = new Http(port);
        }
        return http;
    }

    // ------------------------------------------------------------------ atalhos

    /** Código único por teste (as classes compartilham o mesmo banco). */
    protected static String newCode(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Cadastra uma aplicação pela API de administração. */
    protected void createApplication(String code) {
        Http.Resp resp = http().post("/v1/admin/applications", ADMIN_TOKEN, Map.of("code", code, "name", "App " + code));
        if (resp.status() != 201) {
            throw new IllegalStateException("Falha ao cadastrar a aplicação: " + resp.raw());
        }
    }

    /** Cria uma chave de escrita e devolve o valor em texto puro. */
    protected String createKey(String code) {
        Http.Resp resp = http().post("/v1/admin/applications/" + code + "/keys", ADMIN_TOKEN, null);
        if (resp.status() != 201) {
            throw new IllegalStateException("Falha ao criar a chave: " + resp.raw());
        }
        return resp.body().path("key").asString();
    }

    protected static Map<String, Object> event(String action) {
        return event(UUID.randomUUID(), action, Instant.now());
    }

    protected static Map<String, Object> event(UUID eventId, String action, Instant occurredAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId.toString());
        event.put("occurredAt", occurredAt.toString());
        event.put("service", "auth");
        event.put("action", action);
        event.put("outcome", "SUCCESS");
        event.put("actorId", UUID.randomUUID().toString());
        return event;
    }

    protected static Map<String, Object> batch(Map<String, Object>... events) {
        return Map.of("events", List.of(events));
    }

    protected static Map<String, Object> batch(List<Map<String, Object>> events) {
        return Map.of("events", new ArrayList<>(events));
    }
}
