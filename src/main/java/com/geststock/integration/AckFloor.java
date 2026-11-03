package com.geststock.integration;

/**
 * Limite que les acquittements imposent à un passage de purge (plan § 30.4 ; D5, v12-D4, v17-D4), pour l'entreprise
 * courante : ses liaisons actives ou suspendues, les liaisons révoquées étant exclues.
 */
public sealed interface AckFloor {

	/** Aucune liaison active ou suspendue (dernière révoquée, v12-D4) : pas de limite d'acquittement. */
	record NoLink() implements AckFloor {
	}

	/** Au moins une liaison active ou suspendue sans acquittement (v17-D4) : rien n'est purgeable. */
	record Blocked() implements AckFloor {
	}

	/**
	 * Toutes les liaisons actives ou suspendues ont acquitté.
	 * @param seq plus petit acquittement : rien au-delà n'est purgeable
	 */
	record Acknowledged(long seq) implements AckFloor {

		public Acknowledged {
			if (seq < 0) {
				throw new IllegalArgumentException("Acquittement négatif : " + seq);
			}
		}

	}

	static AckFloor noLink() {
		return new NoLink();
	}

	static AckFloor blocked() {
		return new Blocked();
	}

	static AckFloor acknowledged(long seq) {
		return new Acknowledged(seq);
	}

}
