package com.geststock.integration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Drapeau de publication ({@code geststock.integration.publication.*}, lot 2, G13).
 *
 * @param lockTimeout attente maximale du passage du drapeau ({@code lock_timeout}, {@code statement_timeout}) ;
 *                    pendant ce passage, les ventes qui démarrent attendent derrière lui (file équitable) : le délai
 *                    borne aussi leur attente. 2 s : bien au-delà d'une vente ordinaire (dizaines de millisecondes),
 *                    assez court pour qu'une vente anormalement longue ne fige pas les autres plus de 2 s ;
 *                    au-delà, l'activation répond 503 et peut être refaite
 */
@ConfigurationProperties(prefix = "geststock.integration.publication")
public record PublicationFlagProperties(@DefaultValue("2s") Duration lockTimeout) {

	public PublicationFlagProperties {
		lockTimeout = lockTimeout != null ? lockTimeout : Duration.ofSeconds(2);
		if (lockTimeout.isNegative() || lockTimeout.isZero() || lockTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
			throw new IllegalArgumentException("geststock.integration.publication.lock-timeout : de 1 ms à 1 minute.");
		}
	}

}
