package com.geststock.common.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Permissions de l'utilisateur à l'origine de la requête, telles que calculées par le
 * serveur (autorités de l'authentification courante). Sert aux contrôles d'escalade de
 * privilèges : on ne peut pas accorder un droit que l'on ne possède pas soi-même.
 */
public final class ActorPermissions {

	private ActorPermissions() {
	}

	/** Permissions courantes ; ensemble vide sans authentification. Les {@code ROLE_*} sont exclus. */
	public static Set<String> current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			return Set.of();
		}
		return authentication.getAuthorities().stream()
			.map(GrantedAuthority::getAuthority)
			.filter(authority -> authority != null && !authority.startsWith("ROLE_"))
			.collect(Collectors.toUnmodifiableSet());
	}

}
