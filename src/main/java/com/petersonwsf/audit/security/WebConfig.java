package com.petersonwsf.audit.security;

import com.petersonwsf.audit.config.AuditProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra a autenticação nas rotas {@code /v1/**} e recusa subir com credenciais fracas ou repetidas. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final int MIN_TOKEN_LENGTH = 32;

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor, AuditProperties properties) {
        this.authInterceptor = authInterceptor;
        requireStrongTokens(properties.security());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/v1/**");
    }

    private static void requireStrongTokens(AuditProperties.Security security) {
        String read = security.readToken();
        String admin = security.adminToken();
        if (read == null || read.length() < MIN_TOKEN_LENGTH || admin == null || admin.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalStateException(
                    "AUDIT_READ_TOKEN e AUDIT_ADMIN_TOKEN são obrigatórios e precisam ter ao menos " + MIN_TOKEN_LENGTH
                            + " caracteres (gere com: openssl rand -base64 48)");
        }
        if (read.equals(admin)) {
            throw new IllegalStateException("AUDIT_READ_TOKEN e AUDIT_ADMIN_TOKEN devem ser diferentes");
        }
    }
}
