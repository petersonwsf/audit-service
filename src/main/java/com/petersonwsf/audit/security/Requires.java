package com.petersonwsf.audit.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Papel exigido pela rota. Rota sem a anotação exige {@link Role#ADMIN} (padrão restritivo). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Requires {

    Role value();
}
