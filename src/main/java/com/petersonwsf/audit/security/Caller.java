package com.petersonwsf.audit.security;

import java.util.UUID;

/**
 * Quem chama a rota. Para {@link Role#WRITE}, {@code applicationCode} vem SEMPRE da chave (nunca do corpo da requisição),
 * o que impede uma aplicação de gravar auditoria em nome de outra.
 */
public record Caller(Role role, String applicationCode, UUID keyId) {

    public static final String ATTRIBUTE = "com.petersonwsf.audit.security.Caller";
}
