package com.geststock.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Exige une permission sur un endpoint ou un contrôleur entier, vérifiée par Spring
 * Security avant l'exécution (403 sinon). Raccourci de
 * {@code @PreAuthorize("hasAuthority('...')")} résolu par
 * {@code AnnotationTemplateExpressionDefaults} ({@code SecurityConfig}).
 *
 * <pre>{@code
 * @RequiresPermission(Permissions.PRODUCT_CREATE)
 * ProductResponse create(...) { ... }
 * }</pre>
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAuthority('{value}')")
public @interface RequiresPermission {

	/** Code de permission, de préférence une constante de {@link Permissions}. */
	String value();

}
