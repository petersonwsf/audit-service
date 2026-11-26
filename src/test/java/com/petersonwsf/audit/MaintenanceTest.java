package com.petersonwsf.audit;

import com.petersonwsf.audit.domain.EventRepository;
import com.petersonwsf.audit.service.MaintenanceJobs;
import com.petersonwsf.audit.service.PartitionService;
import com.petersonwsf.audit.support.AbstractAuditTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MaintenanceTest extends AbstractAuditTest {

    @Autowired
    private MaintenanceJobs jobs;

    @Autowired
    private PartitionService partitions;

    @Autowired
    private EventRepository events;

    @Test
    void apagaOsEventosForaDaRetencaoDaAplicacaoEMantemOsRecentes() {
        String code = newCode("ret");
        http().post("/v1/admin/applications", ADMIN_TOKEN, Map.of("code", code, "name", "Retenção", "retentionDays", 30));
        String key = createKey(code);
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        http().post("/v1/events", key, batch(
                event(UUID.randomUUID(), "LOGIN_SUCCESS", old),
                event(UUID.randomUUID(), "LOGOUT", Instant.now().minus(1, ChronoUnit.DAYS))));
        assertThat(count(code)).isEqualTo(2);

        jobs.run();

        assertThat(count(code)).isEqualTo(1);
        assertThat(http().get("/v1/events?application=" + code, READ_TOKEN).body().path("items").get(0).path("action")
                .asString()).isEqualTo("LOGOUT");
    }

    @Test
    void antecipaAsParticoesDosProximosMeses() {
        jobs.run();

        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        assertThat(events.partitionNames())
                .contains(PartitionService.nameOf(current), PartitionService.nameOf(current.plusMonths(1)),
                        PartitionService.nameOf(current.plusMonths(2)));
    }

    @Test
    void removeParticoesInteirasMaisAntigasQueAMaiorRetencao() {
        YearMonth ancient = YearMonth.now(ZoneOffset.UTC).minusYears(12);
        partitions.ensure(ancient);
        assertThat(events.partitionNames()).contains(PartitionService.nameOf(ancient));

        jobs.run();

        assertThat(events.partitionNames()).doesNotContain(PartitionService.nameOf(ancient));
    }

    private int count(String code) {
        return jdbc.sql("select count(*) from audit_event where application_code = :code")
                .param("code", code).query(Integer.class).single();
    }
}
