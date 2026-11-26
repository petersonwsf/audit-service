package com.petersonwsf.audit;

import com.petersonwsf.audit.support.AbstractAuditTest;
import com.petersonwsf.audit.support.Http;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryTest extends AbstractAuditTest {

    private final Instant base = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private String seed(UUID actor) {
        String code = newCode("q");
        createApplication(code);
        String key = createKey(code);
        Map<String, Object> e1 = event(UUID.randomUUID(), "LOGIN_SUCCESS", base);
        e1.put("actorId", actor.toString());
        Map<String, Object> e2 = event(UUID.randomUUID(), "LOGIN_FAILURE", base.plusSeconds(60));
        e2.put("outcome", "FAILURE");
        e2.put("correlationId", "corr-1234567890");
        Map<String, Object> e3 = event(UUID.randomUUID(), "PASSWORD_CHANGE", base.plusSeconds(120));
        e3.put("service", "profile");
        e3.put("actorId", actor.toString());
        Map<String, Object> e4 = event(UUID.randomUUID(), "LOGOUT", base.plusSeconds(180));
        Map<String, Object> e5 = event(UUID.randomUUID(), "LOGIN_FAILURE", base.plusSeconds(240));
        e5.put("outcome", "DENIED");
        http().post("/v1/events", key, batch(e1, e2, e3, e4, e5));
        return code;
    }

    private List<String> actions(Http.Resp resp) {
        List<String> list = new ArrayList<>();
        for (JsonNode item : resp.body().path("items")) {
            list.add(item.path("action").asString());
        }
        return list;
    }

    @Test
    void listaDoMaisNovoParaOMaisAntigo() {
        String code = seed(UUID.randomUUID());

        Http.Resp resp = http().get("/v1/events?application=" + code, READ_TOKEN);

        assertThat(actions(resp)).containsExactly("LOGIN_FAILURE", "LOGOUT", "PASSWORD_CHANGE", "LOGIN_FAILURE", "LOGIN_SUCCESS");
        assertThat(resp.body().path("nextCursor").isNull()).isTrue();
    }

    @Test
    void filtraPorAcaoResultadoServicoAtorECorrelacao() {
        UUID actor = UUID.randomUUID();
        String code = seed(actor);
        String q = "/v1/events?application=" + code;

        assertThat(actions(http().get(q + "&action=LOGIN_FAILURE", READ_TOKEN))).hasSize(2);
        assertThat(actions(http().get(q + "&outcome=DENIED", READ_TOKEN))).containsExactly("LOGIN_FAILURE");
        assertThat(actions(http().get(q + "&service=profile", READ_TOKEN))).containsExactly("PASSWORD_CHANGE");
        assertThat(actions(http().get(q + "&actor=" + actor, READ_TOKEN))).containsExactly("PASSWORD_CHANGE", "LOGIN_SUCCESS");
        assertThat(actions(http().get(q + "&correlationId=corr-1234567890", READ_TOKEN))).containsExactly("LOGIN_FAILURE");
    }

    @Test
    void filtraPorPeriodo() {
        String code = seed(UUID.randomUUID());
        String from = base.plusSeconds(60).toString();
        String to = base.plusSeconds(180).toString();

        Http.Resp resp = http().get("/v1/events?application=" + code + "&from=" + from + "&to=" + to, READ_TOKEN);

        assertThat(actions(resp)).containsExactly("LOGOUT", "PASSWORD_CHANGE", "LOGIN_FAILURE");
        assertThat(http().get("/v1/events?application=" + code + "&from=" + to + "&to=" + from, READ_TOKEN).status())
                .isEqualTo(400);
    }

    @Test
    void paginaPorCursorSemRepetirNemPerder() {
        String code = seed(UUID.randomUUID());
        Set<Long> ids = new LinkedHashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            String url = "/v1/events?application=" + code + "&limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            Http.Resp resp = http().get(url, READ_TOKEN);
            assertThat(resp.status()).isEqualTo(200);
            for (JsonNode item : resp.body().path("items")) {
                assertThat(ids.add(item.path("id").asLong())).as("evento repetido entre páginas").isTrue();
            }
            JsonNode next = resp.body().path("nextCursor");
            cursor = next.isNull() || next.isMissingNode() ? null : next.asString();
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(ids).hasSize(5);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void limiteForaDaFaixaEAjustado() {
        String code = seed(UUID.randomUUID());

        assertThat(http().get("/v1/events?application=" + code + "&limit=0", READ_TOKEN).body().path("items").size())
                .isEqualTo(1);
        assertThat(http().get("/v1/events?application=" + code + "&limit=100000", READ_TOKEN).body().path("items").size())
                .isEqualTo(5);
    }

    @Test
    void entradasInvalidasRetornam4xxComCodigoEstavel() {
        String code = seed(UUID.randomUUID());

        Http.Resp unknown = http().get("/v1/events?application=nao-existe-mesmo", READ_TOKEN);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body().path("codigo").asString()).isEqualTo("aplicacao_nao_encontrada");

        assertThat(http().get("/v1/events?application=" + code + "&cursor=lixo", READ_TOKEN).body().path("codigo").asString())
                .isEqualTo("cursor_invalido");
        assertThat(http().get("/v1/events?application=" + code + "&outcome=TALVEZ", READ_TOKEN).status()).isEqualTo(400);
        assertThat(http().get("/v1/events?application=" + code + "&actor=nao-e-uuid", READ_TOKEN).status()).isEqualTo(400);
        assertThat(http().get("/v1/events", READ_TOKEN).status()).isEqualTo(400);
    }

    @Test
    void devolveODetalheEOsCamposDoEvento() {
        String code = newCode("det");
        createApplication(code);
        String key = createKey(code);
        Map<String, Object> event = event("PROFILE_PRIVACY_CHANGED");
        event.put("targetType", "PROFILE");
        event.put("targetId", "abc");
        event.put("ip", "10.0.0.7");
        event.put("userAgent", "JUnit/1.0");
        event.put("schemaVersion", 1);
        event.put("detail", Map.of("de", "publico", "para", "privado"));
        http().post("/v1/events", key, batch(event));

        JsonNode item = http().get("/v1/events?application=" + code, READ_TOKEN).body().path("items").get(0);

        assertThat(item.path("detail").path("para").asString()).isEqualTo("privado");
        assertThat(item.path("targetType").asString()).isEqualTo("PROFILE");
        assertThat(item.path("ip").asString()).isEqualTo("10.0.0.7");
        assertThat(item.path("actorType").asString()).isEqualTo("USER");
        assertThat(item.path("occurredAt").asString()).isNotBlank();
    }
}
