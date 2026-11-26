package com.petersonwsf.audit.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Primeiro filtro da cadeia: adota o {@code X-Correlation-Id} recebido (se válido) ou cria um, coloca no MDC, devolve no
 * cabeçalho da resposta e registra uma linha de acesso. Nunca registra a query string nem cabeçalhos (podem ter segredos).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try (CorrelationId.Scope ignored = CorrelationId.open(request.getHeader(CorrelationId.HEADER))) {
            response.setHeader(CorrelationId.HEADER, CorrelationId.current());
            try {
                chain.doFilter(request, response);
            } finally {
                logAccess(request, response, start);
            }
        }
    }

    private void logAccess(HttpServletRequest request, HttpServletResponse response, long start) {
        long millis = (System.nanoTime() - start) / 1_000_000;
        boolean quiet = request.getRequestURI().startsWith("/actuator");
        if (quiet && !log.isDebugEnabled()) {
            return;
        }
        String line = "Requisição {} {} -> {} ({} ms)";
        if (quiet) {
            log.debug(line, request.getMethod(), request.getRequestURI(), response.getStatus(), millis);
        } else {
            log.info(line, request.getMethod(), request.getRequestURI(), response.getStatus(), millis);
        }
    }
}
