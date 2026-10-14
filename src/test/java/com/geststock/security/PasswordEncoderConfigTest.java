package com.geststock.security;

import org.junit.jupiter.api.Test;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/** Vérification des mots de passe, y compris pour des hachés posés à la main en base. */
class PasswordEncoderConfigTest {

	private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

	@Test
	void encodesWithTheAlgorithmPrefix() {
		String hash = this.encoder.encode("Optique2026");

		assertThat(hash).startsWith("{bcrypt}$2a$12$");
		assertThat(this.encoder.matches("Optique2026", hash)).isTrue();
		assertThat(this.encoder.matches("Mauvais2026", hash)).isFalse();
	}

	@Test
	void verifiesARawBcryptHashWithoutPrefix() {
		String raw = new BCryptPasswordEncoder(4).encode("Optique2026");

		assertThat(raw).doesNotStartWith("{");
		assertThat(this.encoder.matches("Optique2026", raw)).isTrue();
		assertThat(this.encoder.matches("Mauvais2026", raw)).isFalse();
	}

	@Test
	void unknownHashFormatIsRefusedInsteadOfFailing() {
		assertThat(this.encoder.matches("Optique2026", "Optique2026")).isFalse();
		assertThat(this.encoder.matches("Optique2026", "{md5}5f4dcc3b5aa765d61d8327deb882cf99")).isFalse();
	}

}
