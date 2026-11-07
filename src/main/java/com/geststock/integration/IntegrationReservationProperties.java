package com.geststock.integration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de la réservation de stock pour les commandes en ligne (lot 3, étape G28).
 *
 * @param ttl durée de vie d'une réservation (30 minutes par défaut, configurable)
 */
@ConfigurationProperties(prefix = "geststock.integration.reservation")
public record IntegrationReservationProperties(@DefaultValue("30m") Duration ttl) {

	public IntegrationReservationProperties {
		ttl = ttl != null ? ttl : Duration.ofMinutes(30);
	}

}
