package com.geststock.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import com.geststock.role.RoleService;
import com.geststock.user.UserAccount;
import com.geststock.user.UserAccountService;

/**
 * Transforme un JWT (signature et expiration déjà vérifiées) en utilisateur courant.
 *
 * <p>Le jeton ne fait foi que pour l'identité. Le compte est relu en base à chaque requête
 * et rejeté (401) s'il n'existe plus, est désactivé, est verrouillé ou si son mot de passe
 * a changé après l'émission du jeton. Les autorités sont celles des rôles actuels en base :
 * {@code ROLE_<code>} et chaque permission {@code domaine:action}. Un compte sans rôle
 * n'obtient aucune permission.
 */
@Component
public class JwtUserAuthenticationConverter implements JwtAuthenticationResolver {

	private final UserAccountService accounts;

	private final RoleService roles;

	private final Clock clock;

	JwtUserAuthenticationConverter(UserAccountService accounts, RoleService roles, Clock clock) {
		this.accounts = accounts;
		this.roles = roles;
		this.clock = clock;
	}

	@Override
	public AbstractAuthenticationToken resolve(Jwt jwt) {
		UUID userId = parseSubject(jwt.getSubject());
		UserAccount account = this.accounts.findById(userId)
			.orElseThrow(() -> new InvalidBearerTokenException("Compte inconnu"));
		if (!account.active()) {
			throw new InvalidBearerTokenException("Compte désactivé");
		}
		if (account.isLockedAt(Instant.now(this.clock))) {
			throw new InvalidBearerTokenException("Compte verrouillé");
		}
		if (issuedBeforeCredentialsChange(jwt, account)) {
			throw new InvalidBearerTokenException("Jeton antérieur au dernier changement de mot de passe");
		}

		Set<String> permissions = this.roles.permissionsOf(account.roles());
		List<GrantedAuthority> authorities = new ArrayList<>();
		account.roles().forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
		permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));

		CurrentUser user = new CurrentUser(account.id(), account.username(), account.roles(), permissions,
				account.defaultWarehouseId(), account.warehouseIds());
		return new CurrentUserAuthentication(user, jwt, authorities);
	}

	/**
	 * {@code iat} est à la seconde près : on compare à l'instant de changement tronqué à la
	 * seconde, pour ne pas rejeter le jeton émis juste après un changement de mot de passe.
	 */
	private static boolean issuedBeforeCredentialsChange(Jwt jwt, UserAccount account) {
		Instant changedAt = account.credentialsChangedAt();
		Instant issuedAt = jwt.getIssuedAt();
		return changedAt != null && (issuedAt == null || issuedAt.isBefore(changedAt.truncatedTo(ChronoUnit.SECONDS)));
	}

	private static UUID parseSubject(String subject) {
		try {
			return UUID.fromString(subject);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new InvalidBearerTokenException("Sujet du jeton invalide");
		}
	}

}
