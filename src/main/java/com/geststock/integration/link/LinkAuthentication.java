package com.geststock.integration.link;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Authentification d'un appel de la chaîne d'intégration par jeton de liaison. Aucune autorité : les portées sont lues
 * dans {@link LinkPrincipal} par la chaîne d'intégration, jamais confondues avec les permissions des utilisateurs.
 */
public final class LinkAuthentication extends AbstractAuthenticationToken {

	private final LinkPrincipal principal;

	public LinkAuthentication(LinkPrincipal principal) {
		super(List.of());
		this.principal = principal;
		setAuthenticated(true);
	}

	@Override
	public LinkPrincipal getPrincipal() {
		return this.principal;
	}

	/** Le jeton n'est pas conservé après la vérification. */
	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public String getName() {
		return this.principal.linkId().toString();
	}

}
