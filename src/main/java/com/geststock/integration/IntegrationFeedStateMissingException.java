package com.geststock.integration;

/**
 * La ligne unique de {@code integration_feed_state} a disparu : le séquenceur ne peut plus attribuer de
 * {@code seq}. Aucune réparation automatique ; procédure manuelle décrite dans {@link FeedSequencer}.
 */
public class IntegrationFeedStateMissingException extends IllegalStateException {

	public IntegrationFeedStateMissingException() {
		super("Ligne d'état du flux d'intégration absente (integration_feed_state) : séquencement impossible. "
				+ "Réparation : voir la Javadoc de FeedSequencer (recréer la ligne avec last_seq = COALESCE(MAX(seq), 0), "
				+ "jamais 0 si des événements sont déjà séquencés).");
	}

}
