package com.geststock.platform.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Super Admin authentifié. Autorité {@value #AUTHORITY}, ou seulement {@value #PASSWORD_CHANGE_AUTHORITY}
 * tant que le mot de passe initial n'a pas été changé (profil et changement de mot de passe uniquement).
 * Aucune permission métier, aucune entreprise : les routes métier lui sont fermées.
 */
public class PlatformAuthentication extends AbstractAuthenticationToken {

	public static final String ROLE = "SUPER_ADMIN";

	public static final String AUTHORITY = "ROLE_" + ROLE;

	public static final String PASSWORD_CHANGE_ROLE = "SUPER_ADMIN_PASSWORD_CHANGE";

	public static final String PASSWORD_CHANGE_AUTHORITY = "ROLE_" + PASSWORD_CHANGE_ROLE;

	private final Principal principal;

	public PlatformAuthentication(Principal principal, boolean passwordChangeRequired) {
		super(List.of(new SimpleGrantedAuthority(passwordChangeRequired ? PASSWORD_CHANGE_AUTHORITY : AUTHORITY)));
		this.principal = principal;
		setAuthenticated(true);
	}

	@Override
	public Principal getPrincipal() {
		return this.principal;
	}

	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public String getName() {
		return this.principal.id().toString();
	}

	/** Super Admin de la requête ({@code @AuthenticationPrincipal PlatformAuthentication.Principal}). */
	public record Principal(UUID id, String username, String fullName) {
	}

}
