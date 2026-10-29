package com.geststock.integration;

/**
 * La ligne unique de {@code integration_feed_state} a disparu : le séquenceur ne peut plus attribuer de
 * {@code seq}. Aucune réparation automatique ; procédure manuelle (v11-D1) décrite dans {@link FeedSequencer}.
 */
public class IntegrationFeedStateMissingException extends IllegalStateException {

	public IntegrationFeedStateMissingException() {
		super("Ligne d'état du flux d'intégration absente (integration_feed_state) : séquencement impossible. "
				+ "Réparation (v11-D1, Javadoc de FeedSequencer) : recréer la ligne avec last_seq = MAX(seq) et "
				+ "purged_through_seq = MIN(seq) - 1 sur les événements séquencés, (0, 0) s'il n'y en a aucun. "
				+ "Invariant de purge : l'événement de plus grand seq n'est jamais purgé.");
	}

}
