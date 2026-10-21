package com.geststock.platform.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import com.geststock.security.PlatformTokenResolver;

/**
 * Jeton Super Admin : le compte est relu en base plateforme à chaque requête (actif, non verrouillé,
 * jeton postérieur au dernier changement de mot de passe), comme pour les utilisateurs d'entreprise.
 */
@Component
class PlatformTokenResolverImpl implements PlatformTokenResolver {

	private final PlatformAdminStore admins;

	private final Clock clock;

	PlatformTokenResolverImpl(PlatformAdminStore admins, Clock clock) {
		this.admins = admins;
		this.clock = clock;
	}

	@Override
	public AbstractAuthenticationToken resolve(Jwt jwt) {
		UUID id;
		try {
			id = UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new InvalidBearerTokenException("Jeton invalide");
		}
		PlatformAdmin admin = this.admins.findById(id).orElseThrow(() -> new InvalidBearerTokenException("Compte inconnu"));
		if (!admin.active() || admin.isLockedAt(Instant.now(this.clock))) {
			throw new InvalidBearerTokenException("Compte inactif");
		}
		Instant changedAt = admin.credentialsChangedAt();
		Instant issuedAt = jwt.getIssuedAt();
		if (changedAt != null && (issuedAt == null || issuedAt.isBefore(changedAt.truncatedTo(ChronoUnit.SECONDS)))) {
			throw new InvalidBearerTokenException("Jeton antérieur au dernier changement de mot de passe");
		}
		return new PlatformAuthentication(new PlatformAuthentication.Principal(admin.id(), admin.username(), admin.fullName()),
				admin.mustChangePassword());
	}

}
