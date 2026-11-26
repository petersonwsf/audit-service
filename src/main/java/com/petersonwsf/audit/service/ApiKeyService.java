package com.petersonwsf.audit.service;

import com.petersonwsf.audit.domain.ApiKey;
import com.petersonwsf.audit.domain.ApiKeyRepository;
import com.petersonwsf.audit.security.Caller;
import com.petersonwsf.audit.security.Role;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

/** Geração e verificação das chaves de escrita. Só o hash SHA-256 vai ao banco (a chave tem 256 bits aleatórios). */
@Service
public class ApiKeyService {

    private static final String KEY_PREFIX = "ak_";
    private static final int PREFIX_LENGTH = 10;

    /** Uma chave recém-criada: o valor em texto puro existe só aqui, uma única vez. */
    public record Generated(String plainKey, String prefix, byte[] hash) {
    }

    private final SecureRandom random = new SecureRandom();
    private final ApiKeyRepository keys;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
    }

    public Generated generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String plain = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Generated(plain, plain.substring(0, PREFIX_LENGTH), hash(plain));
    }

    /** Chave válida (ativa, de aplicação ativa) -> quem chama, com o código da aplicação vindo da chave. */
    public Optional<Caller> authenticate(String plainKey) {
        if (plainKey == null || !plainKey.startsWith(KEY_PREFIX) || plainKey.length() > 128) {
            return Optional.empty();
        }
        Optional<ApiKey> key = keys.findActiveByHash(hash(plainKey));
        key.ifPresent(k -> keys.touch(k.id(), clock.instant()));
        return key.map(k -> new Caller(Role.WRITE, k.applicationCode(), k.id()));
    }

    static byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
