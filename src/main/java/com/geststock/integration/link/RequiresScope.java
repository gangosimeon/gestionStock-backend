package com.geststock.integration.link;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Portée exigée par une route d'intégration ({@code /api/integration/v1/**}, contrat : {@code x-required-scopes}).
 * Vérifiée par la chaîne de sécurité de l'intégration avant l'exécution : jeton de liaison valide et portée accordée,
 * sinon {@code 401 INVALID_TOKEN} ou {@code 403 INSUFFICIENT_SCOPE}. Une route d'intégration sans cette annotation est
 * refusée, et fait échouer le build ({@code EndpointSecurityCoverageIT}). Jamais combinée avec {@code @PreAuthorize}
 * de la chaîne principale.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresScope {

	/**
	 * Toute portée accordée suffit (contrat : {@code GET /link}, {@code POST /link/revoke}, {@code POST /link/secret}).
	 * Réservé aux routes listées dans {@code IntegrationSecurityConfig.ANY_SCOPE_ENDPOINTS}.
	 */
	String ANY = "*";

	/** Portée exigée, par exemple {@code catalog:read}, ou {@link #ANY}. */
	String value();

	/**
	 * Route servie aussi pendant une suspension de la liaison (v14-D10 : {@code GET /link}, {@code POST /link/revoke}) ;
	 * ailleurs, une liaison suspendue reçoit {@code 403 LINK_SUSPENDED}. Réservé aux routes listées dans
	 * {@code IntegrationSecurityConfig.SUSPENSION_ALLOWED_ENDPOINTS}.
	 */
	boolean allowedWhileSuspended() default false;

	/**
	 * Route qui ne touche que la base plateforme (C2 : {@code POST /link/revoke}, la boutique peut toujours se
	 * déconnecter) : ni contrôle du statut de l'entreprise ({@code COMPANY_*}) ni de la migration de sa base
	 * ({@code SCHEMA_UPGRADE_*}), et aucune entreprise courante posée. Les contrôles du jeton, de la liaison et de la
	 * portée s'appliquent toujours. Réservé aux routes listées dans {@code IntegrationSecurityConfig.PLATFORM_ONLY_ENDPOINTS}.
	 */
	boolean platformOnly() default false;

}
