package com.geststock.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signature et validation des jetons d'accès : HMAC-SHA256 avec une clé connue du seul
 * serveur. La validation vérifie la signature, l'algorithme (pas de {@code alg: none}),
 * l'émetteur, la présence et la validité de l'expiration et de la date d'émission (tolérance
 * d'horloge de 30 secondes).
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

	static final int MIN_SECRET_BYTES = 32;

	static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

	private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

	@Bean
	SecretKey jwtSigningKey(GestStockSecurityProperties properties) {
		return signingKey(properties.jwt());
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
		return NimbusJwtEncoder.withSecretKey(jwtSigningKey).build();
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtSigningKey, GestStockSecurityProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey).macAlgorithm(MacAlgorithm.HS256).build();
		// exp et iat obligatoires : JwtTimestampValidator ne contrôle que les claims présents, et un
		// jeton sans exp serait valable indéfiniment.
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
				new JwtClaimValidator<Instant>(JwtClaimNames.IAT, Objects::nonNull),
				new JwtTimestampValidator(CLOCK_SKEW), new JwtIssuerValidator(properties.jwt().issuer())));
		return decoder;
	}

	static SecretKey signingKey(GestStockSecurityProperties.Jwt jwt) {
		String secret = jwt.secret();
		if (secret == null || secret.isBlank()) {
			if (!jwt.allowEphemeralSecret()) {
				throw new IllegalStateException(
						"Clé JWT absente : définir GESTSTOCK_JWT_SECRET (au moins " + MIN_SECRET_BYTES + " octets).");
			}
			log.warn("Aucune clé JWT configurée : clé aléatoire générée (sessions invalidées à chaque redémarrage). "
					+ "Interdit en production : définir GESTSTOCK_JWT_SECRET.");
			byte[] random = new byte[MIN_SECRET_BYTES];
			new SecureRandom().nextBytes(random);
			return new SecretKeySpec(random, "HmacSHA256");
		}
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"Clé JWT trop courte : " + bytes.length + " octets, au moins " + MIN_SECRET_BYTES + " requis.");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

}
