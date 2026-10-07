package com.geststock.common.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Identifiant (UUID) de l'utilisateur à l'origine de la requête, porté par
 * {@link Authentication#getName()} (claim {@code sub} du JWT).
 */
public final class ActorIdentity {

	private ActorIdentity() {
	}

	/** Utilisateur authentifié, ou vide (tâche système, migration, appel anonyme). */
	public static Optional<UUID> currentUserId() {
		return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
			.filter(Authentication::isAuthenticated)
			.filter(auth -> !(auth instanceof AnonymousAuthenticationToken))
			.flatMap(auth -> parse(auth.getName()));
	}

	private static Optional<UUID> parse(String value) {
		try {
			return Optional.of(UUID.fromString(value));
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			return Optional.empty();
		}
	}

}
