package com.petersonwsf.audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Partições mensais de {@code audit_event}: criadas sob demanda (ao receber eventos) e antecipadas pela rotina. */
@Service
public class PartitionService {

    private static final Logger log = LoggerFactory.getLogger(PartitionService.class);
    private static final Pattern NAME = Pattern.compile("audit_event_(\\d{4})_(\\d{2})");
    private static final long LOCK_KEY = 727_001L;

    private final JdbcClient jdbc;
    private final TransactionTemplate independentTransaction;
    private final Set<YearMonth> known = ConcurrentHashMap.newKeySet();

    public PartitionService(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static String nameOf(YearMonth month) {
        return "audit_event_%04d_%02d".formatted(month.getYear(), month.getMonthValue());
    }

    public static Optional<YearMonth> monthOf(String partitionName) {
        Matcher matcher = NAME.matcher(partitionName);
        return matcher.matches()
                ? Optional.of(YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))))
                : Optional.empty();
    }

    public static YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(ZoneOffset.UTC));
    }

    /** Garante a partição do mês (em transação própria: o que foi criado não se perde se o chamador falhar). */
    public void ensure(YearMonth month) {
        if (known.contains(month)) {
            return;
        }
        independentTransaction.executeWithoutResult(status -> create(month));
        known.add(month);
    }

    private void create(YearMonth month) {
        // Serializa a criação (várias instâncias/threads) para não disputar o lock de catálogo do PostgreSQL.
        jdbc.sql("select 1 from (select pg_advisory_xact_lock(:key)) t").param("key", LOCK_KEY).query(Integer.class).single();
        // Os limites vêm de um YearMonth (inteiros), nunca de texto do cliente.
        String from = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().toString();
        String to = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime().toString();
        jdbc.sql("create table if not exists %s partition of audit_event for values from ('%s') to ('%s')"
                .formatted(nameOf(month), from, to)).update();
        log.info("Partição {} garantida", nameOf(month));
    }

    /** Remove uma partição pelo nome (só aceita o padrão {@code audit_event_AAAA_MM}). */
    public void drop(String partitionName) {
        if (monthOf(partitionName).isEmpty()) {
            throw new IllegalArgumentException("Nome de partição inválido");
        }
        jdbc.sql("drop table if exists " + partitionName).update();
        known.remove(monthOf(partitionName).orElseThrow());
        log.info("Partição {} removida (fora da retenção)", partitionName);
    }
}
