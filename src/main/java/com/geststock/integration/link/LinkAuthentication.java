package com.geststock.integration.link;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import com.geststock.tenancy.TenantRef;

/**
 * Authentification d'un appel de la chaîne d'intégration par jeton de liaison. Aucune autorité : les portées sont lues
 * dans {@link LinkPrincipal} par la chaîne d'intégration, jamais confondues avec les permissions des utilisateurs.
 * Porte aussi l'état relu en base à cet appel (statut de la liaison, entreprise et son statut), utilisé par
 * l'autorisation et pour établir l'entreprise courante.
 */
public final class LinkAuthentication extends AbstractAuthenticationToken {

	private final LinkPrincipal principal;

	private final LinkStatus linkStatus;

	private final TenantRef company;

	private final String companyStatus;

	public LinkAuthentication(LinkPrincipal principal, LinkStatus linkStatus, TenantRef company, String companyStatus) {
		super(List.of());
		this.principal = principal;
		this.linkStatus = linkStatus;
		this.company = company;
		this.companyStatus = companyStatus;
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

	LinkStatus linkStatus() {
		return this.linkStatus;
	}

	/** Entreprise de la liaison : seule source de l'entreprise courante des appels d'intégration. */
	TenantRef company() {
		return this.company;
	}

	String companyStatus() {
		return this.companyStatus;
	}

}
