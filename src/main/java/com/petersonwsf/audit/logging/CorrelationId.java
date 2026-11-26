package com.petersonwsf.audit.logging;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Id de correlação: acompanha uma ação por todos os serviços para achar no Loki/Grafana todas as linhas de um mesmo fluxo.
 * Vive no MDC (chave {@value #MDC_KEY}); trabalho fora de uma requisição HTTP (rotinas agendadas) abre o seu escopo.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Só este formato é aceito de fora: impede injeção de linhas de log (CR/LF) e valores gigantes. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    private CorrelationId() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /** Abre um escopo com o id informado (ou um novo, se vier vazio/inválido); ao fechar restaura o anterior. */
    public static Scope open(String id) {
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, isValid(id) ? id : UUID.randomUUID().toString());
        return new Scope(previous);
    }

    public static final class Scope implements AutoCloseable {

        private final String previous;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
