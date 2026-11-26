package com.petersonwsf.audit;

import com.petersonwsf.audit.support.AbstractAuditTest;
import com.petersonwsf.audit.support.Http;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTest extends AbstractAuditTest {

    @Test
    void cadastraAplicacaoEValidaOCodigo() {
        String code = newCode("adm");

        Http.Resp created = http().post("/v1/admin/applications", ADMIN_TOKEN,
                Map.of("code", code, "name", "Minha aplicação", "retentionDays", 90));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("retentionDays").asInt()).isEqualTo(90);
        assertThat(created.body().path("active").asBoolean()).isTrue();

        assertThat(http().post("/v1/admin/applications", ADMIN_TOKEN, Map.of("code", code, "name", "de novo"))
                .body().path("codigo").asString()).isEqualTo("aplicacao_ja_existe");
        assertThat(http().post("/v1/admin/applications", ADMIN_TOKEN, Map.of("code", "Inválido!", "name", "x"))
                .body().path("codigo").asString()).isEqualTo("codigo_invalido");
        assertThat(http().post("/v1/admin/applications", ADMIN_TOKEN, Map.of("code", newCode("sn"), "name", " "))
                .body().path("codigo").asString()).isEqualTo("nome_invalido");
        assertThat(http().post("/v1/admin/applications", ADMIN_TOKEN,
                Map.of("code", newCode("rt"), "name", "x", "retentionDays", 1)).body().path("codigo").asString())
                .isEqualTo("retencao_invalida");
    }

    @Test
    void atualizaAplicacao() {
        String code = newCode("upd");
        createApplication(code);

        Http.Resp resp = http().patch("/v1/admin/applications/" + code, ADMIN_TOKEN,
                Map.of("name", "Novo nome", "retentionDays", 30));

        assertThat(resp.status()).isEqualTo(200);
        assertThat(resp.body().path("name").asString()).isEqualTo("Novo nome");
        assertThat(resp.body().path("retentionDays").asInt()).isEqualTo(30);
        assertThat(http().patch("/v1/admin/applications/inexistente-x", ADMIN_TOKEN, Map.of("name", "n")).status())
                .isEqualTo(404);
    }

    @Test
    void chaveApareceUmaUnicaVezEFicaSoOHashNoBanco() {
        String code = newCode("key");
        createApplication(code);

        Http.Resp created = http().post("/v1/admin/applications/" + code + "/keys", ADMIN_TOKEN, null);
        String plain = created.body().path("key").asString();

        assertThat(created.status()).isEqualTo(201);
        assertThat(plain).startsWith("ak_").hasSizeGreaterThan(40);
        assertThat(created.body().path("prefix").asString()).isEqualTo(plain.substring(0, 10));

        // Nada guarda o valor: nem a listagem de chaves, nem o banco.
        Http.Resp listed = http().get("/v1/admin/applications/" + code + "/keys", ADMIN_TOKEN);
        assertThat(listed.raw()).doesNotContain(plain);
        assertThat(listed.body().get(0).has("key")).isFalse();
        Integer matches = jdbc.sql("select count(*) from api_key where encode(key_hash, 'escape') = :plain or key_prefix = :plain")
                .param("plain", plain).query(Integer.class).single();
        assertThat(matches).isZero();
    }

    @Test
    void limitaAsChavesAtivasERevogaSemAfetarAsOutras() {
        String code = newCode("lim");
        createApplication(code);
        Http.Resp k1 = http().post("/v1/admin/applications/" + code + "/keys", ADMIN_TOKEN, null);
        String key2 = createKey(code);

        Http.Resp third = http().post("/v1/admin/applications/" + code + "/keys", ADMIN_TOKEN, null);
        assertThat(third.status()).isEqualTo(409);
        assertThat(third.body().path("codigo").asString()).isEqualTo("limite_de_chaves");

        String id1 = k1.body().path("id").asString();
        assertThat(http().delete("/v1/admin/applications/" + code + "/keys/" + id1, ADMIN_TOKEN).status()).isEqualTo(204);

        // A revogada para de funcionar; a outra continua; e abre vaga para uma nova (rotação sem parar nada).
        assertThat(http().post("/v1/events", k1.body().path("key").asString(), batch(event("LOGOUT"))).status()).isEqualTo(401);
        assertThat(http().post("/v1/events", key2, batch(event("LOGOUT"))).status()).isEqualTo(200);
        assertThat(http().post("/v1/admin/applications/" + code + "/keys", ADMIN_TOKEN, null).status()).isEqualTo(201);
        assertThat(http().delete("/v1/admin/applications/" + code + "/keys/" + java.util.UUID.randomUUID(), ADMIN_TOKEN)
                .status()).isEqualTo(404);
    }

    @Test
    void registraOUltimoUsoDaChave() {
        String code = newCode("use");
        createApplication(code);
        String key = createKey(code);
        assertThat(http().get("/v1/admin/applications/" + code + "/keys", ADMIN_TOKEN).body().get(0).path("lastUsedAt").isNull())
                .isTrue();

        http().post("/v1/events", key, batch(event("LOGOUT")));

        assertThat(http().get("/v1/admin/applications/" + code + "/keys", ADMIN_TOKEN).body().get(0).path("lastUsedAt").isNull())
                .isFalse();
    }

    @Test
    void rotasDeAdministracaoExigemOTokenDeAdmin() {
        assertThat(http().get("/v1/admin/applications", READ_TOKEN).status()).isEqualTo(401);
        assertThat(http().get("/v1/admin/applications", null).status()).isEqualTo(401);
        assertThat(http().post("/v1/admin/applications", READ_TOKEN, Map.of("code", newCode("x"), "name", "x")).status())
                .isEqualTo(401);
        assertThat(http().get("/v1/admin/applications", ADMIN_TOKEN).status()).isEqualTo(200);
    }

    @Test
    void listaResumidaDeAplicacoesParaALeitura() {
        String code = newCode("sum");
        createApplication(code);

        Http.Resp resp = http().get("/v1/applications", READ_TOKEN);

        assertThat(resp.status()).isEqualTo(200);
        List<String> codes = new ArrayList<>();
        for (JsonNode app : resp.body()) {
            codes.add(app.path("code").asString());
            assertThat(app.has("retentionDays")).isFalse();
        }
        assertThat(codes).contains(code, "audit-service");
        assertThat(http().get("/v1/applications", ADMIN_TOKEN).status()).isEqualTo(401);
    }

    @Test
    void auditaAPropriaAdministracao() {
        String code = newCode("self");
        createApplication(code);
        createKey(code);
        http().patch("/v1/admin/applications/" + code, ADMIN_TOKEN, Map.of("active", false));

        Http.Resp resp = http().get("/v1/events?application=audit-service&limit=100", READ_TOKEN);

        List<String> mine = new ArrayList<>();
        for (JsonNode item : resp.body().path("items")) {
            String target = item.path("targetId").asString();
            if (target.equals(code)) {
                mine.add(item.path("action").asString());
                assertThat(item.path("actorType").asString()).isEqualTo("ADMIN");
            }
            if (item.path("action").asString().equals("KEY_CREATED")
                    && item.path("detail").path("application").asString().equals(code)) {
                mine.add("KEY_CREATED");
            }
        }
        assertThat(mine).contains("APPLICATION_CREATED", "APPLICATION_UPDATED", "KEY_CREATED");
    }
}
