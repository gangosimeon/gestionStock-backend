package com.geststock.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.geststock.common.security.ActorIdentity;

/**
 * Audit technique JPA : renseigne {@code createdAt/By} et {@code updatedAt/By} des entités
 * héritant de {@link com.geststock.common.jpa.AuditableEntity}.
 *
 * <p>L'auteur est l'identifiant (UUID) de l'utilisateur authentifié ({@link ActorIdentity}), porté par
 * {@code Authentication#getName()} (claim {@code sub} du JWT). Sans utilisateur
 * authentifié (tâche système, migration), l'auteur reste {@code null}.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

	@Bean
	AuditorAware<UUID> auditorAware() {
		return ActorIdentity::currentUserId;
	}

	@Bean
	DateTimeProvider auditingDateTimeProvider(Clock clock) {
		return () -> Optional.of(Instant.now(clock));
	}

}
