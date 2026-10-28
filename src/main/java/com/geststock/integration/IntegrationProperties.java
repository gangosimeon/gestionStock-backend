package com.geststock.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Paramètres de l'intégration boutique en ligne ({@code geststock.integration.*}).
 *
 * @param publish publication des événements dans le flux
 */
@ConfigurationProperties(prefix = "geststock.integration")
public record IntegrationProperties(@DefaultValue Publish publish) {

	/**
	 * @param enabled        publication active ; désactivée par défaut (activée en dev et en test) :
	 *                       {@link IntegrationEvents} n'écrit alors rien
	 * @param maxPayloadSize taille maximale de la charge sérialisée d'un événement
	 */
	public record Publish(@DefaultValue("false") boolean enabled, @DefaultValue("16KB") DataSize maxPayloadSize) {
	}

}
