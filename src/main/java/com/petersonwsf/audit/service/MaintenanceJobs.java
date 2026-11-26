package com.petersonwsf.audit.service;

import com.petersonwsf.audit.config.AuditProperties;
import com.petersonwsf.audit.domain.Application;
import com.petersonwsf.audit.domain.ApplicationRepository;
import com.petersonwsf.audit.domain.EventRepository;
import com.petersonwsf.audit.logging.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Rotinas periódicas: antecipa as partições dos próximos meses e aplica a retenção de cada aplicação. */
@Component
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);

    private final AuditProperties properties;
    private final ApplicationRepository applications;
    private final EventRepository events;
    private final PartitionService partitions;
    private final Clock clock;

    public MaintenanceJobs(AuditProperties properties, ApplicationRepository applications, EventRepository events,
                           PartitionService partitions, Clock clock) {
        this.properties = properties;
        this.applications = applications;
        this.events = events;
        this.partitions = partitions;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${audit.retention.interval}")
    public void scheduled() {
        if (!properties.retention().enabled()) {
            return;
        }
        try (CorrelationId.Scope ignored = CorrelationId.open(null)) {
            run();
        } catch (RuntimeException e) {
            log.error("Falha na rotina de manutenção da auditoria", e);
        }
    }

    /** Uma execução completa (também usada nos testes). */
    public void run() {
        prepareFuturePartitions();
        long deleted = applyRetention();
        int dropped = dropExpiredPartitions();
        log.info("Manutenção concluída: {} evento(s) vencido(s) apagado(s), {} partição(ões) removida(s)", deleted, dropped);
    }

    private void prepareFuturePartitions() {
        YearMonth current = PartitionService.monthOf(clock.instant());
        for (int i = 0; i <= properties.retention().partitionsAhead(); i++) {
            partitions.ensure(current.plusMonths(i));
        }
    }

    /** Apaga, em lotes curtos, os eventos mais antigos que a retenção de cada aplicação. */
    private long applyRetention() {
        long total = 0;
        for (Application application : applications.findAll()) {
            Instant cutoff = clock.instant().minus(application.retentionDays(), ChronoUnit.DAYS);
            int deleted;
            do {
                deleted = events.deleteOlderThan(application.code(), cutoff, properties.retention().deleteBatchSize());
                total += deleted;
            } while (deleted >= properties.retention().deleteBatchSize());
        }
        return total;
    }

    /** Remove partições inteiras mais antigas que a MAIOR retenção entre as aplicações (nada nelas pode ser mantido). */
    private int dropExpiredPartitions() {
        Instant limit = clock.instant().minus(applications.maxRetentionDays(), ChronoUnit.DAYS);
        int dropped = 0;
        for (String name : events.partitionNames()) {
            var month = PartitionService.monthOf(name);
            if (month.isPresent() && !month.get().plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(limit)) {
                partitions.drop(name);
                dropped++;
            }
        }
        return dropped;
    }
}
