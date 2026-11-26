package com.petersonwsf.audit;

import com.petersonwsf.audit.support.AbstractAuditTest;
import com.petersonwsf.audit.support.Http;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestTest extends AbstractAuditTest {

    @Test
    void aceitaOLoteEGravaSobAAplicacaoDaChave() {
        String code = newCode("ing");
        createApplication(code);
        String key = createKey(code);

        Map<String, Object> first = event("LOGIN_SUCCESS");
        Map<String, Object> second = event("PASSWORD_CHANGE");
        // Um código de aplicação enviado no corpo é ignorado: vale o da chave.
        first.put("application", "audit-service");
        first.put("applicationCode", "outra-app");

        Http.Resp resp = http().post("/v1/events", key, batch(first, second));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().path("accepted").asInt()).isEqualTo(2);
        assertThat(resp.body().path("duplicates").asInt()).isZero();
        assertThat(resp.body().path("rejected").size()).isZero();

        Http.Resp list = http().get("/v1/events?application=" + code, READ_TOKEN);
        assertThat(list.body().path("items").size()).isEqualTo(2);
        assertThat(list.body().path("items").get(0).path("application").asString()).isEqualTo(code);
    }

    @Test
    void reenvioDoMesmoEventoNaoDuplica() {
        String code = newCode("idem");
        createApplication(code);
        String key = createKey(code);
        Map<String, Object> event = event("REGISTER");

        Http.Resp first = http().post("/v1/events", key, batch(event));
        Http.Resp again = http().post("/v1/events", key, batch(event));

        assertThat(first.body().path("accepted").asInt()).isEqualTo(1);
        assertThat(again.body().path("accepted").asInt()).isZero();
        assertThat(again.body().path("duplicates").asInt()).isEqualTo(1);
        assertThat(http().get("/v1/events?application=" + code, READ_TOKEN).body().path("items").size()).isEqualTo(1);
    }

    @Test
    void recusaSoOEventoInvalidoSemDerrubarOLote() {
        String code = newCode("inv");
        createApplication(code);
        String key = createKey(code);

        Map<String, Object> semAcao = event("X");
        semAcao.remove("action");
        Map<String, Object> comEmail = event("PROFILE_UPDATED");
        comEmail.put("detail", Map.of("userEmail", "a@b.com"));
        Map<String, Object> futuro = event(UUID.randomUUID(), "LOGOUT", Instant.now().plus(2, ChronoUnit.DAYS));
        Map<String, Object> ok = event("LOGOUT");
        ok.put("detail", Map.of("campos", List.of("nome", "bio")));

        Http.Resp resp = http().post("/v1/events", key, batch(semAcao, comEmail, futuro, ok));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().path("accepted").asInt()).isEqualTo(1);
        assertThat(resp.body().path("rejected").size()).isEqualTo(3);
        assertThat(resp.body().path("rejected").get(1).path("reason").asString()).contains("email");
    }

    @Test
    void recusaDetalheGrandeDemais() {
        String code = newCode("big");
        createApplication(code);
        String key = createKey(code);
        Map<String, Object> event = event("POST_DELETED");
        event.put("detail", Map.of("texto", "x".repeat(5000)));

        Http.Resp resp = http().post("/v1/events", key, batch(event));

        assertThat(resp.body().path("accepted").asInt()).isZero();
        assertThat(resp.body().path("rejected").get(0).path("reason").asString()).contains("bytes");
    }

    @Test
    void recusaLoteVazioEGrandeDemais() {
        String code = newCode("lote");
        createApplication(code);
        String key = createKey(code);

        assertThat(http().post("/v1/events", key, Map.of("events", List.of())).status()).isEqualTo(400);

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            many.add(event("LOGOUT"));
        }
        assertThat(http().post("/v1/events", key, batch(many)).status()).isEqualTo(413);
    }

    @Test
    void semChaveOuComChaveInvalidaRetorna401() {
        String code = newCode("auth");
        createApplication(code);

        assertThat(http().post("/v1/events", null, batch(event("LOGOUT"))).status()).isEqualTo(401);
        assertThat(http().post("/v1/events", "ak_chave-que-nao-existe", batch(event("LOGOUT"))).status()).isEqualTo(401);
        // Os tokens de leitura e de administração não servem para gravar.
        assertThat(http().post("/v1/events", READ_TOKEN, batch(event("LOGOUT"))).status()).isEqualTo(401);
        assertThat(http().post("/v1/events", ADMIN_TOKEN, batch(event("LOGOUT"))).status()).isEqualTo(401);
    }

    @Test
    void chaveDeEscritaNaoLe() {
        String code = newCode("ro");
        createApplication(code);
        String key = createKey(code);

        assertThat(http().get("/v1/events?application=" + code, key).status()).isEqualTo(401);
        assertThat(http().get("/v1/events?application=" + code, ADMIN_TOKEN).status()).isEqualTo(401);
        assertThat(http().get("/v1/events?application=" + code, null).status()).isEqualTo(401);
    }

    @Test
    void umaAplicacaoNaoEnxergaEmOutraNemGravaEmNomeDela() {
        String a = newCode("a");
        String b = newCode("b");
        createApplication(a);
        createApplication(b);
        String keyA = createKey(a);

        http().post("/v1/events", keyA, batch(event("LOGIN_SUCCESS")));

        assertThat(http().get("/v1/events?application=" + a, READ_TOKEN).body().path("items").size()).isEqualTo(1);
        assertThat(http().get("/v1/events?application=" + b, READ_TOKEN).body().path("items").size()).isZero();
    }

    @Test
    void chaveRevogadaOuAplicacaoDesativadaNaoGrava() {
        String code = newCode("rev");
        createApplication(code);
        String key = createKey(code);
        assertThat(http().post("/v1/events", key, batch(event("LOGOUT"))).status()).isEqualTo(200);

        http().patch("/v1/admin/applications/" + code, ADMIN_TOKEN, Map.of("active", false));
        assertThat(http().post("/v1/events", key, batch(event("LOGOUT"))).status()).isEqualTo(401);

        http().patch("/v1/admin/applications/" + code, ADMIN_TOKEN, Map.of("active", true));
        assertThat(http().post("/v1/events", key, batch(event("LOGOUT"))).status()).isEqualTo(200);
    }

    @Test
    void corpoMalFormadoRetorna400ComOFormatoPadraoDeErro() {
        String code = newCode("body");
        createApplication(code);
        String key = createKey(code);

        Http.Resp resp = http().post("/v1/events", key, "{isso nao e json");

        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.body().path("sucesso").asBoolean(true)).isFalse();
        assertThat(resp.body().path("codigo").asString()).isEqualTo("requisicao_invalida");
        assertThat(resp.body().path("mensagem").asString()).isNotBlank();
    }

    @Test
    void criaAParticaoDoMesDoEventoAntigo() {
        String code = newCode("part");
        createApplication(code);
        String key = createKey(code);
        Instant old = Instant.now().minus(120, ChronoUnit.DAYS);

        Http.Resp resp = http().post("/v1/events", key, batch(event(UUID.randomUUID(), "LOGOUT", old)));

        assertThat(resp.body().path("accepted").asInt()).isEqualTo(1);
    }
}
