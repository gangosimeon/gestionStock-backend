package com.geststock.security;

import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Authentification issue d'un jeton d'accès validé. {@link #getName()} renvoie l'identifiant
 * (UUID) de l'utilisateur : il alimente {@code created_by}/{@code updated_by} (JPA auditing).
 */
public class CurrentUserAuthentication extends AbstractAuthenticationToken {

	private final CurrentUser principal;

	private final transient Jwt token;

	public CurrentUserAuthentication(CurrentUser principal, Jwt token, Collection<? extends GrantedAuthority> authorities) {
		super(authorities);
		this.principal = principal;
		this.token = token;
		setAuthenticated(true);
	}

	@Override
	public CurrentUser getPrincipal() {
		return this.principal;
	}

	/** Le jeton n'est pas exposé comme « credentials » (jamais journalisé ni sérialisé). */
	@Override
	public Object getCredentials() {
		return null;
	}

	public Jwt getToken() {
		return this.token;
	}

	@Override
	public String getName() {
		return this.principal.id().toString();
	}

}
