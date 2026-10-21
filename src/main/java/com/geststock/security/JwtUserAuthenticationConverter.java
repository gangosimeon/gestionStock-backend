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

import com.geststock.exception.ErrorCode;
import com.geststock.role.RoleService;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDirectory;
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

	private final TenantDirectory directory;

	private final PlatformTokenResolver platformTokens;

	JwtUserAuthenticationConverter(UserAccountService accounts, RoleService roles, Clock clock, TenantDirectory directory,
			PlatformTokenResolver platformTokens) {
		this.accounts = accounts;
		this.roles = roles;
		this.clock = clock;
		this.directory = directory;
		this.platformTokens = platformTokens;
	}

	@Override
	public AbstractAuthenticationToken resolve(Jwt jwt) {
		String type = jwt.getClaimAsString(JwtTokenService.TYPE_CLAIM);
		if (JwtTokenService.PLATFORM_TYPE.equals(type)) {
			return this.platformTokens.resolve(jwt);
		}
		if (!JwtTokenService.TENANT_TYPE.equals(type)) {
			throw new InvalidBearerTokenException("Type de jeton inconnu");
		}
		enterCompany(jwt);
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
	 * Base de l'entreprise inscrite dans le jeton (claim signé, jamais une donnée du client). Le compte
	 * est ensuite relu dans cette base : un jeton de l'entreprise A ne désigne aucun compte de B.
	 */
	private void enterCompany(Jwt jwt) {
		UUID companyId = parseUuid(jwt.getClaimAsString(JwtTokenService.TENANT_CLAIM));
		TenantDirectory.TenantStatus company = this.directory.findById(companyId)
			.orElseThrow(() -> new InvalidBearerTokenException("Entreprise inconnue"));
		if (company.isSuspended()) {
			throw new CompanyAccessException(ErrorCode.COMPANY_SUSPENDED);
		}
		if (!company.isActive()) {
			throw new CompanyAccessException(ErrorCode.COMPANY_UNAVAILABLE);
		}
		TenantContext.set(company.ref());
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
		return parseUuid(subject);
	}

	private static UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			throw new InvalidBearerTokenException("Jeton invalide");
		}
	}

}
