package com.petersonwsf.audit.security;

/** Papéis de acesso, cada um com a sua credencial: escrita (chave da aplicação), leitura e administração (tokens do .env). */
public enum Role {
    WRITE,
    READ,
    ADMIN
}
