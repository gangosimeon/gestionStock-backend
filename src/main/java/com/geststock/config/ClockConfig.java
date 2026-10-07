package com.geststock.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Horloge unique de l'application (UTC). Injecter {@link Clock} plutôt que d'appeler
 * {@code Instant.now()} directement rend le code testable.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
