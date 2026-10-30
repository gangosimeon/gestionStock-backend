package com.geststock.integration.link;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Authentification du client JeCréeMaBoutique (HTTP Basic) sur {@code /oauth/par} et {@code /oauth/token}. Aucune
 * autorité ; jamais confondue avec un jeton de liaison ni avec un utilisateur.
 */
public final class ClientAuthentication extends AbstractAuthenticationToken {

	private final IntegrationClients.AuthenticatedClient client;

	public ClientAuthentication(IntegrationClients.AuthenticatedClient client) {
		super(List.of());
		this.client = client;
		setAuthenticated(true);
	}

	@Override
	public IntegrationClients.AuthenticatedClient getPrincipal() {
		return this.client;
	}

	/** Le secret client n'est pas conservé après la vérification. */
	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public String getName() {
		return this.client.clientId();
	}

}
