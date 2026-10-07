package com.geststock.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Exécute le test au nom d'un utilisateur authentifié détenant les permissions indiquées
 * (ou toutes si {@link #all()}), comme si le serveur les avait calculées depuis ses rôles.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@WithSecurityContext(factory = WithPermissionsSecurityContextFactory.class)
public @interface WithPermissions {

	String[] value() default {};

	boolean all() default false;

}
