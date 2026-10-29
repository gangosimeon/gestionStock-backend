package com.geststock.integration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paramètres du séquenceur du flux d'intégration ({@code geststock.integration.sequencer.*}).
 *
 * @param batchSize        nombre maximal d'événements séquencés par appel
 * @param lockTimeout      {@code lock_timeout} de la transaction d'attribution
 * @param statementTimeout {@code statement_timeout} de la transaction d'attribution
 */
@ConfigurationProperties(prefix = "geststock.integration.sequencer")
public record FeedSequencerProperties(@DefaultValue("1000") int batchSize, @DefaultValue("2s") Duration lockTimeout,
		@DefaultValue("5s") Duration statementTimeout) {
}
