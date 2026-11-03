package com.geststock.integration;

import org.springframework.stereotype.Component;

/**
 * Maintenance du flux de l'entreprise courante (lot 3, G20b ; décision A) : seul point d'entrée de la maintenance de
 * l'intégration vers la purge ({@link OutboxPurge}) et le séquenceur ({@link FeedSequencer}), qui restent internes au
 * flux. Chaque opération est contrôlée par le passage ({@link PassControl}) avant chaque lot.
 */
@Component
public class FeedMaintenance {

	private final OutboxPurge purge;

	private final FeedSequencer sequencer;

	FeedMaintenance(OutboxPurge purge, FeedSequencer sequencer) {
		this.purge = purge;
		this.sequencer = sequencer;
	}

	/** Purge dans le mode configuré ({@code SIMULATION} par défaut), bail et budget vérifiés avant chaque lot. */
	public PurgeReport purge(PassControl control) {
		return this.purge.run(control);
	}

	/**
	 * Séquencement des événements validés sans {@code seq} (G20-D9 : seulement quand l'entreprise n'a aucune liaison
	 * active ou suspendue, ce que vérifie l'appelant), lot après lot. Un lot plein n'est pas une erreur : le séquencement
	 * continue tant que le bail est tenu et qu'il reste du budget ; le premier lot a lieu si le bail est tenu. Un autre
	 * séquenceur au travail (lot ignoré) : rien à faire de plus, il avance le flux lui-même.
	 */
	public SequencingOutcome sequence(PassControl control) {
		int assigned = 0;
		while (true) {
			if (!control.leaseHeld()) {
				return new SequencingOutcome(assigned, PassStop.LEASE_LOST);
			}
			SequencingResult result = this.sequencer.sequence();
			assigned += result.assigned();
			if (result.skipped() || !result.limitReached()) {
				return new SequencingOutcome(assigned, PassStop.COMPLETE);
			}
			if (!control.timeLeft()) {
				return new SequencingOutcome(assigned, PassStop.LIMIT_REACHED);
			}
		}
	}

}
