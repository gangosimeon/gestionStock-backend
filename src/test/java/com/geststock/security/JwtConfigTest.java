package com.geststock.security;

import java.time.Duration;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aucune clé faible ni absente n'est acceptée hors mode éphémère explicite (dev/test). */
class JwtConfigTest {

	@Test
	void refusesMissingSecretOutsideDevAndTest() {
		assertThatThrownBy(() -> JwtConfig.signingKey(jwt(null, false)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("GESTSTOCK_JWT_SECRET");
		assertThatThrownBy(() -> JwtConfig.signingKey(jwt("  ", false))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesSecretShorterThan256Bits() {
		assertThatThrownBy(() -> JwtConfig.signingKey(jwt("trop-court", true)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("trop courte");
	}

	@Test
	void usesConfiguredSecret() {
		SecretKey key = JwtConfig.signingKey(jwt("une-cle-de-production-tres-longue-0123456789", false));
		assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
		assertThat(key.getEncoded()).hasSizeGreaterThanOrEqualTo(32);
	}

	@Test
	void generatesRandomKeyOnlyWhenExplicitlyAllowed() {
		SecretKey first = JwtConfig.signingKey(jwt(null, true));
		SecretKey second = JwtConfig.signingKey(jwt(null, true));
		assertThat(first.getEncoded()).hasSize(32).isNotEqualTo(second.getEncoded());
	}

	private static GestStockSecurityProperties.Jwt jwt(String secret, boolean allowEphemeral) {
		return new GestStockSecurityProperties.Jwt(secret, "geststock", Duration.ofMinutes(15), Duration.ofDays(7),
				allowEphemeral);
	}

}
