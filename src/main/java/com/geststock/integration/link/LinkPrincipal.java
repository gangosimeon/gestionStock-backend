package com.geststock.integration.link;

import java.util.Set;
import java.util.UUID;

/**
 * Appelant authentifié de la chaîne d'intégration : la liaison du jeton, son client et ses portées. L'entreprise n'y
 * figure pas : elle est relue depuis la liaison, avec son statut, à chaque appel (étape G7).
 *
 * @param linkId   liaison
 * @param clientId client JeCréeMaBoutique
 * @param scopes   portées accordées par le jeton
 */
public record LinkPrincipal(UUID linkId, String clientId, Set<String> scopes) {

	/** {@code orders:write} implique {@code orders:read} (décision C5) ; aucune autre implication. */
	private static final String ORDERS_READ = "orders:read";

	private static final String ORDERS_WRITE = "orders:write";

	public LinkPrincipal {
		scopes = Set.copyOf(scopes);
	}

	public static LinkPrincipal of(LinkTokenClaims claims) {
		return new LinkPrincipal(claims.linkId(), claims.clientId(), claims.scopes());
	}

	/** Portée accordée, implication C5 comprise. */
	public boolean hasScope(String scope) {
		return this.scopes.contains(scope) || (ORDERS_READ.equals(scope) && this.scopes.contains(ORDERS_WRITE));
	}

}
