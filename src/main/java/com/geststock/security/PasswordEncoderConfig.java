package com.geststock.security;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Hachage des mots de passe. Les hachés sont préfixés par leur algorithme
 * ({@code {bcrypt}$2a$12$...}) : un changement d'algorithme futur (Argon2) ne nécessitera
 * aucune migration, les anciens hachés restant vérifiables.
 *
 * <p>Un haché bcrypt <b>sans préfixe</b> ({@code $2a$12$...}, typiquement posé à la main
 * en SQL) reste vérifié par bcrypt ; tout autre haché non reconnu est simplement refusé.
 * Sans ce repli, {@code matches()} lève une {@code IllegalArgumentException} et la
 * connexion répond 500 au lieu de 401.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

	/** Coût bcrypt : 2^12 itérations (≈ 250 ms), compromis sécurité / temps de connexion. */
	static final int BCRYPT_STRENGTH = 12;

	@Bean
	PasswordEncoder passwordEncoder() {
		BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(BCRYPT_STRENGTH);
		DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", bcrypt));
		encoder.setDefaultPasswordEncoderForMatches(bcrypt);
		return encoder;
	}

}
