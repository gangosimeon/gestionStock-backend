package com.geststock.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.geststock.tenancy.CurrentTenant;
import com.geststock.user.UserAccount;

/**
 * Émission des jetons d'accès (JWT signés HS256, durée courte).
 *
 * <p>Contenu : {@code sub} (UUID utilisateur), {@code username}, {@code iss}, {@code iat},
 * {@code exp}, {@code jti}. Les rôles n'y figurent qu'à titre informatif (affichage) :
 * les autorisations sont toujours recalculées depuis la base à chaque requête.
 */
@Service
public class JwtTokenService {

	static final String USERNAME_CLAIM = "username";

	static final String ROLES_CLAIM = "roles";

	/** Type de jeton : {@value #TENANT_TYPE} (utilisateur d'entreprise) ou {@value #PLATFORM_TYPE} (Super Admin). */
	public static final String TYPE_CLAIM = "typ";

	/** Entreprise du jeton d'un utilisateur d'entreprise (identifiant interne, signé par le serveur). */
	public static final String TENANT_CLAIM = "tid";

	public static final String TENANT_TYPE = "tenant";

	public static final String PLATFORM_TYPE = "platform";

	private final JwtEncoder encoder;

	private final GestStockSecurityProperties.Jwt properties;

	private final Clock clock;

	private final CurrentTenant currentTenant;

	JwtTokenService(JwtEncoder encoder, GestStockSecurityProperties properties, Clock clock, CurrentTenant currentTenant) {
		this.encoder = encoder;
		this.properties = properties.jwt();
		this.clock = clock;
		this.currentTenant = currentTenant;
	}

	/** Jeton d'un utilisateur d'entreprise : l'entreprise courante y est inscrite ({@code tid}). */
	public AccessToken issue(UserAccount account) {
		JwtClaimsSet.Builder claims = baseClaims(account.id(), account.username())
			.claim(TYPE_CLAIM, TENANT_TYPE)
			.claim(TENANT_CLAIM, this.currentTenant.require().companyId().toString())
			.claim(ROLES_CLAIM, List.copyOf(account.roles()));
		return encode(claims);
	}

	/** Jeton d'un Super Admin : aucune entreprise, valable uniquement sur {@code /api/platform/**}. */
	public AccessToken issuePlatform(UUID adminId, String username) {
		return encode(baseClaims(adminId, username).claim(TYPE_CLAIM, PLATFORM_TYPE));
	}

	private JwtClaimsSet.Builder baseClaims(UUID subject, String username) {
		Instant now = Instant.now(this.clock);
		return JwtClaimsSet.builder()
			.issuer(this.properties.issuer())
			.subject(subject.toString())
			.issuedAt(now)
			.expiresAt(now.plus(this.properties.accessTokenTtl()))
			.id(UUID.randomUUID().toString())
			.claim(USERNAME_CLAIM, username);
	}

	private AccessToken encode(JwtClaimsSet.Builder builder) {
		JwtClaimsSet claims = builder.build();
		Instant expiresAt = claims.getExpiresAt();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
		String value = this.encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(value, this.properties.accessTokenTtl().toSeconds(), expiresAt);
	}

	/**
	 * Jeton d'accès émis.
	 *
	 * @param value            JWT signé
	 * @param expiresInSeconds durée de validité (contrat front : {@code expiresIn} en secondes)
	 * @param expiresAt        instant d'expiration
	 */
	public record AccessToken(String value, long expiresInSeconds, Instant expiresAt) {

		@Override
		public String toString() {
			return "AccessToken[value=***, expiresAt=" + this.expiresAt + "]";
		}

	}

}
