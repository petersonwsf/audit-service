package com.petersonwsf.audit.service;

import com.petersonwsf.audit.config.AuditProperties;
import com.petersonwsf.audit.domain.ApiKey;
import com.petersonwsf.audit.domain.ApiKeyRepository;
import com.petersonwsf.audit.domain.Application;
import com.petersonwsf.audit.domain.ApplicationRepository;
import com.petersonwsf.audit.domain.EventRepository;
import com.petersonwsf.audit.domain.NewEvent;
import com.petersonwsf.audit.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petersonwsf.audit.logging.CorrelationId;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Administração: cadastro de aplicações e de chaves de escrita. Toda mudança é auditada sob a aplicação "audit-service". */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    public static final String SELF_APPLICATION = "audit-service";
    private static final String SELF_SERVICE = "admin-api";
    static final int MAX_ACTIVE_KEYS = 2;
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_-]{1,39}");

    /** Chave recém-criada com o valor em texto puro (mostrado uma única vez). */
    public record CreatedKey(ApiKey key, String plainKey) {
    }

    private final ApplicationRepository applications;
    private final ApiKeyRepository keys;
    private final EventRepository events;
    private final ApiKeyService keyService;
    private final PartitionService partitions;
    private final AuditProperties properties;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, ApiKeyRepository keys, EventRepository events,
                              ApiKeyService keyService, PartitionService partitions, AuditProperties properties,
                              Clock clock) {
        this.applications = applications;
        this.keys = keys;
        this.events = events;
        this.keyService = keyService;
        this.partitions = partitions;
        this.properties = properties;
        this.clock = clock;
    }

    public List<Application> list() {
        return applications.findAll();
    }

    public Application get(String code) {
        return applications.find(code)
                .orElseThrow(() -> ApiException.notFound("aplicacao_nao_encontrada", "Aplicação não cadastrada"));
    }

    @Transactional
    public Application create(String code, String name, Integer retentionDays) {
        String cleanCode = code == null ? "" : code.trim();
        if (!CODE.matcher(cleanCode).matches()) {
            throw ApiException.badRequest("codigo_invalido",
                    "O código deve ter 2 a 40 caracteres: letras minúsculas, números, '-' ou '_'");
        }
        String cleanName = requireName(name);
        int days = validRetention(retentionDays == null ? properties.retention().defaultDays() : retentionDays);
        Application application = new Application(cleanCode, cleanName, true, days, clock.instant());
        try {
            applications.insert(application);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("aplicacao_ja_existe", "Já existe uma aplicação com este código");
        }
        audit("APPLICATION_CREATED", "APPLICATION", cleanCode, null);
        log.info("Aplicação {} cadastrada (retenção de {} dias)", cleanCode, days);
        return application;
    }

    @Transactional
    public Application update(String code, String name, Boolean active, Integer retentionDays) {
        Application current = get(code);
        Application updated = new Application(code,
                name == null ? current.name() : requireName(name),
                active == null ? current.active() : active,
                retentionDays == null ? current.retentionDays() : validRetention(retentionDays),
                current.createdAt());
        applications.update(updated);
        audit("APPLICATION_UPDATED", "APPLICATION", code,
                "{\"active\":" + updated.active() + ",\"retentionDays\":" + updated.retentionDays() + "}");
        log.info("Aplicação {} atualizada (ativa: {}, retenção: {} dias)", code, updated.active(), updated.retentionDays());
        return updated;
    }

    public List<ApiKey> listKeys(String code) {
        get(code);
        return keys.findByApplication(code);
    }

    @Transactional
    public CreatedKey createKey(String code) {
        get(code);
        if (keys.countActive(code) >= MAX_ACTIVE_KEYS) {
            throw ApiException.conflict("limite_de_chaves",
                    "A aplicação já tem " + MAX_ACTIVE_KEYS + " chaves ativas; revogue uma antes de criar outra");
        }
        ApiKeyService.Generated generated = keyService.generate();
        ApiKey key = new ApiKey(UUID.randomUUID(), code, generated.prefix(), clock.instant(), null, null);
        keys.insert(key, generated.hash());
        audit("KEY_CREATED", "API_KEY", key.id().toString(), "{\"application\":\"" + code + "\"}");
        log.info("Chave {} criada para a aplicação {}", key.id(), code);
        return new CreatedKey(key, generated.plainKey());
    }

    @Transactional
    public void revokeKey(String code, UUID keyId) {
        ApiKey key = keys.find(code, keyId)
                .orElseThrow(() -> ApiException.notFound("chave_nao_encontrada", "Chave não encontrada"));
        if (!key.active()) {
            return;
        }
        keys.revoke(keyId, clock.instant());
        audit("KEY_REVOKED", "API_KEY", keyId.toString(), "{\"application\":\"" + code + "\"}");
        log.info("Chave {} da aplicação {} revogada", keyId, code);
    }

    // ------------------------------------------------------------------ auditoria da própria administração

    private void audit(String action, String targetType, String targetId, String detailJson) {
        Instant now = clock.instant();
        partitions.ensure(PartitionService.monthOf(now));
        events.insert(SELF_APPLICATION, new NewEvent(UUID.randomUUID(), now, SELF_SERVICE, action, "SUCCESS", null, "ADMIN",
                targetType, targetId, null, null, CorrelationId.current(), 1, detailJson), now);
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > 120) {
            throw ApiException.badRequest("nome_invalido", "O nome é obrigatório (até 120 caracteres)");
        }
        return clean;
    }

    private static int validRetention(int days) {
        if (days < 7 || days > 3650) {
            throw ApiException.badRequest("retencao_invalida", "A retenção deve ficar entre 7 e 3650 dias");
        }
        return days;
    }
}
