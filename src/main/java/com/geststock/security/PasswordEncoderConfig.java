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
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfig {

	/** Coût bcrypt : 2^12 itérations (≈ 250 ms), compromis sécurité / temps de connexion. */
	static final int BCRYPT_STRENGTH = 12;

	@Bean
	PasswordEncoder passwordEncoder() {
		return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_STRENGTH)));
	}

}
