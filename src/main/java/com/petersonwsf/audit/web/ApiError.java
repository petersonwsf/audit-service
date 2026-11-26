package com.petersonwsf.audit.web;

/** Corpo público de erro: {@code { "mensagem": "...", "sucesso": false, "codigo": "..." }}. */
public record ApiError(String mensagem, boolean sucesso, String codigo) {

    public static ApiError of(String codigo, String mensagem) {
        return new ApiError(mensagem, false, codigo);
    }
}
