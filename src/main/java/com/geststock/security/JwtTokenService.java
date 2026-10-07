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

	private final JwtEncoder encoder;

	private final GestStockSecurityProperties.Jwt properties;

	private final Clock clock;

	JwtTokenService(JwtEncoder encoder, GestStockSecurityProperties properties, Clock clock) {
		this.encoder = encoder;
		this.properties = properties.jwt();
		this.clock = clock;
	}

	public AccessToken issue(UserAccount account) {
		Instant now = Instant.now(this.clock);
		Instant expiresAt = now.plus(this.properties.accessTokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(this.properties.issuer())
			.subject(account.id().toString())
			.issuedAt(now)
			.expiresAt(expiresAt)
			.id(UUID.randomUUID().toString())
			.claim(USERNAME_CLAIM, account.username())
			.claim(ROLES_CLAIM, List.copyOf(account.roles()))
			.build();
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
