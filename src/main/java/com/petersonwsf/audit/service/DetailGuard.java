package com.petersonwsf.audit.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Proteção contra dado pessoal ou segredo no {@code detail}: recusa o evento cujo JSON tem uma chave suspeita
 * (senha, token, e-mail, telefone, CPF...). Também limita a profundidade e a quantidade de nós.
 */
@Component
public class DetailGuard {

    static final Set<String> FORBIDDEN_FRAGMENTS = Set.of(
            "password", "senha", "secret", "token", "cookie", "authorization",
            "email", "phone", "telefone", "cpf", "birth", "nascimento");

    private static final int MAX_DEPTH = 6;
    private static final int MAX_NODES = 500;

    /** Primeira chave proibida (ou um motivo estrutural), se houver. */
    public Optional<String> violation(JsonNode detail) {
        int[] nodes = {0};
        return inspect(detail, 0, nodes);
    }

    private Optional<String> inspect(JsonNode node, int depth, int[] nodes) {
        if (++nodes[0] > MAX_NODES) {
            return Optional.of("detail com nós demais");
        }
        if (depth > MAX_DEPTH) {
            return Optional.of("detail muito profundo");
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                for (String fragment : FORBIDDEN_FRAGMENTS) {
                    if (key.contains(fragment)) {
                        return Optional.of("detail contém a chave proibida '" + fragment + "'");
                    }
                }
                Optional<String> inner = inspect(entry.getValue(), depth + 1, nodes);
                if (inner.isPresent()) {
                    return inner;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Optional<String> inner = inspect(child, depth + 1, nodes);
                if (inner.isPresent()) {
                    return inner;
                }
            }
        }
        return Optional.empty();
    }
}
