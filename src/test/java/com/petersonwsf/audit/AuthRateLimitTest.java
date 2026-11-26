package com.petersonwsf.audit;

import com.petersonwsf.audit.support.AbstractAuditTest;
import com.petersonwsf.audit.support.Http;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Contexto próprio (limite baixo de falhas) para não interferir nos demais testes, que compartilham o IP 127.0.0.1. */
@TestPropertySource(properties = "audit.security.failed-auth-per-minute=3")
class AuthRateLimitTest extends AbstractAuditTest {

    @Test
    void bloqueiaOIpAposMuitasFalhasDeAutenticacao() {
        for (int i = 0; i < 3; i++) {
            assertThat(http().get("/v1/applications", "token-errado").status()).isEqualTo(401);
        }

        Http.Resp blocked = http().get("/v1/applications", READ_TOKEN);

        assertThat(blocked.status()).isEqualTo(429);
        assertThat(blocked.body().path("codigo").asString()).isEqualTo("muitas_requisicoes");
    }
}
