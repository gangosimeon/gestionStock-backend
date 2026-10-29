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

	/** Portée exigée, par exemple {@code catalog:read}. */
	String value();

}
