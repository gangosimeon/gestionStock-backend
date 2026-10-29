package com.geststock.integration;

/**
 * Résultat d'un appel du séquenceur ({@link FeedSequencer#sequence()}).
 *
 * @param assigned     nombre d'événements qui ont reçu un {@code seq} lors de cet appel
 * @param lastSeq      dernier {@code seq} attribué et validé connu à l'issue de l'appel
 * @param skipped      appel ignoré : un autre séquenceur tenait le verrou (aucune attente, aucune attribution)
 * @param limitReached le lot était plein : d'autres événements attendent peut-être leur {@code seq}
 */
public record SequencingResult(int assigned, long lastSeq, boolean skipped, boolean limitReached) {

	static SequencingResult skipped(long lastSeq) {
		return new SequencingResult(0, lastSeq, true, false);
	}

}
