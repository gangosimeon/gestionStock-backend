package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.support.PostgresTestConfiguration;

/**
 * Étanchéité entre jetons utilisateurs et jetons de liaison (lot 2, étape G5 ; v13-D5) dans le contexte réel :
 * un jeton de liaison est refusé par le {@link JwtDecoder} de la chaîne principale, un jeton utilisateur valide
 * est refusé par {@link IntegrationTokenService}. L'injection existante est inchangée : un seul
 * {@link JwtEncoder}, un seul {@link JwtDecoder} et une seule clé de signature dans le contexte.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class IntegrationTokenIsolationIT {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private JwtDecoder userDecoder;

	@Autowired
	private JwtEncoder userEncoder;

	@Autowired
	private IntegrationTokenService links;

	@Test
	void theExistingInjectionIsUnchanged() {
		assertThat(this.context.getBeanNamesForType(JwtDecoder.class)).containsExactly("jwtDecoder");
		assertThat(this.context.getBeanNamesForType(JwtEncoder.class)).containsExactly("jwtEncoder");
		assertThat(this.context.getBeanNamesForType(SecretKey.class)).containsExactly("jwtSigningKey");
	}

	@Test
	void aLinkTokenIsRefusedByTheMainChainDecoder() {
		String linkToken = this.links.issue(UUID.randomUUID(), "jcmb-test", Set.of("catalog:read")).value();

		assertThat(this.links.verify(linkToken).clientId()).isEqualTo("jcmb-test");
		assertThatThrownBy(() -> this.userDecoder.decode(linkToken)).isInstanceOf(JwtException.class);
	}

	@Test
	void aValidUserTokenIsRefusedByTheIntegrationTokenService() {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("geststock").subject(UUID.randomUUID().toString())
			.issuedAt(now).expiresAt(now.plusSeconds(900)).id(UUID.randomUUID().toString()).claim("typ", "tenant")
			.claim("tid", UUID.randomUUID().toString()).claim("username", "admin").build();
		String userToken = this.userEncoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
			.getTokenValue();

		assertThat(this.userDecoder.decode(userToken).getSubject()).as("jeton utilisateur accepté par la chaîne principale")
			.isEqualTo(claims.getSubject());
		assertThatThrownBy(() -> this.links.verify(userToken)).isInstanceOf(InvalidLinkTokenException.class);
	}

}
