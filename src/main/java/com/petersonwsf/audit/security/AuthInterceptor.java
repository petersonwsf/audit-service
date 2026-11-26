package com.petersonwsf.audit.security;

import com.petersonwsf.audit.config.AuditProperties;
import com.petersonwsf.audit.service.ApiKeyService;
import com.petersonwsf.audit.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * Autentica as rotas {@code /v1/**} pelo papel exigido ({@link Requires}):
 * escrita = chave da aplicação (hash no banco); leitura e administração = tokens do ambiente, comparados em tempo constante.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);
    private static final String BEARER = "Bearer ";

    private final AuditProperties properties;
    private final ApiKeyService apiKeys;
    private final RateLimiter limiter;

    public AuthInterceptor(AuditProperties properties, ApiKeyService apiKeys, RateLimiter limiter) {
        this.properties = properties;
        this.apiKeys = apiKeys;
        this.limiter = limiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Requires requires = method.getMethodAnnotation(Requires.class);
        Role required = requires == null ? Role.ADMIN : requires.value();

        String failuresKey = "auth-fail:" + request.getRemoteAddr();
        if (limiter.current(failuresKey) >= properties.security().failedAuthPerMinute()) {
            throw new ApiException.TooManyRequests(limiter.retryAfterSeconds(failuresKey));
        }

        Caller caller = authenticate(required, bearerToken(request));
        if (caller == null) {
            limiter.hit(failuresKey);
            log.warn("Autenticação recusada para a rota {} {} (papel exigido: {})",
                    request.getMethod(), request.getRequestURI(), required);
            throw ApiException.unauthorized();
        }
        if (required == Role.WRITE) {
            enforceKeyRate(caller);
        }
        request.setAttribute(Caller.ATTRIBUTE, caller);
        return true;
    }

    private Caller authenticate(Role required, String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return switch (required) {
            case WRITE -> {
                Optional<Caller> caller = apiKeys.authenticate(token);
                yield caller.orElse(null);
            }
            case READ -> sameSecret(token, properties.security().readToken()) ? new Caller(Role.READ, null, null) : null;
            case ADMIN -> sameSecret(token, properties.security().adminToken()) ? new Caller(Role.ADMIN, null, null) : null;
        };
    }

    private void enforceKeyRate(Caller caller) {
        String key = "key:" + caller.keyId();
        if (limiter.hit(key) > properties.limits().requestsPerMinutePerKey()) {
            log.warn("Limite de requisições por minuto excedido pela aplicação {}", caller.applicationCode());
            throw new ApiException.TooManyRequests(limiter.retryAfterSeconds(key));
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith(BEARER) ? header.substring(BEARER.length()).trim() : null;
    }

    /** Compara os hashes dos dois valores em tempo constante (não vaza o tamanho nem o conteúdo do segredo). */
    private static boolean sameSecret(String given, String expected) {
        return expected != null && !expected.isBlank()
                && MessageDigest.isEqual(sha256(given), sha256(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
