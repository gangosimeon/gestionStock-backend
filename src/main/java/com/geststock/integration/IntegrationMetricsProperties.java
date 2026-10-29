package com.geststock.integration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Métriques du flux d'intégration ({@code geststock.integration.metrics.*}), désactivées par défaut.
 *
 * @param enabled   job planifié de collecte actif
 * @param interval  intervalle entre deux collectes
 * @param warnAfter âge du plus ancien événement sans {@code seq} au-delà duquel un WARN est journalisé
 */
@ConfigurationProperties(prefix = "geststock.integration.metrics")
public record IntegrationMetricsProperties(@DefaultValue("false") boolean enabled, @DefaultValue("60s") Duration interval,
		@DefaultValue("5m") Duration warnAfter) {
}
