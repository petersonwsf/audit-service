package com.petersonwsf.audit.web;

import org.springframework.http.HttpStatus;

/** Erro de negócio com status HTTP e código estável para o cliente. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "nao_autenticado", "Autenticação necessária ou credencial inválida");
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException tooLarge(String code, String message) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, code, message);
    }

    /** 429 com o tempo (segundos) até a próxima tentativa. */
    public static class TooManyRequests extends ApiException {

        private final long retryAfterSeconds;

        public TooManyRequests(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "muitas_requisicoes",
                    "Muitas requisições em pouco tempo. Aguarde um pouco e tente novamente.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }
}
