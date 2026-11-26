package com.petersonwsf.audit;

import com.petersonwsf.audit.service.DetailGuard;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DetailGuardTest {

    private final DetailGuard guard = new DetailGuard();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void aceitaDetalheSemDadoPessoal() {
        assertThat(guard.violation(mapper.readTree("{\"campos\":[\"nome\",\"bio\"],\"de\":\"publico\",\"score\":0.42}")))
                .isEmpty();
    }

    @Test
    void recusaChavesComDadoPessoalOuSegredo() {
        for (String key : new String[]{"password", "novaSenha", "accessToken", "Email", "telefone", "cpf", "dataNascimento",
                "cookie", "authorization"}) {
            assertThat(guard.violation(mapper.readTree("{\"" + key + "\":\"x\"}"))).as(key).isPresent();
        }
    }

    @Test
    void encontraChaveProibidaAninhadaEmObjetoEEmLista() {
        assertThat(guard.violation(mapper.readTree("{\"a\":{\"b\":{\"userEmail\":\"x\"}}}"))).isPresent();
        assertThat(guard.violation(mapper.readTree("{\"itens\":[{\"ok\":1},{\"secret\":2}]}"))).isPresent();
    }

    @Test
    void recusaJsonProfundoDemais() {
        assertThat(guard.violation(mapper.readTree("{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":{\"a\":1}}}}}}}}")))
                .isPresent();
    }
}
