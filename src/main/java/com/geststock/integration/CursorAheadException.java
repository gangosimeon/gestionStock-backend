package com.geststock.integration;

/**
 * Curseur en avance : {@code after} dépasse le dernier {@code seq} attribué. Cas anormal (curseur d'une
 * autre entreprise, état du flux réinitialisé ou corrompu) : jamais de page vide silencieuse, le
 * consommateur continuerait d'attendre des {@code seq} qui ne viendront qu'après un saut. Code et statut
 * HTTP non fixés par le contrat (ajout à décider) : erreur interne jusque-là.
 */
public class CursorAheadException extends IllegalStateException {

	private final long after;

	private final long lastSeq;

	public CursorAheadException(long after, long lastSeq) {
		super("Curseur du flux en avance : after = " + after + " > dernier seq attribué = " + lastSeq);
		this.after = after;
		this.lastSeq = lastSeq;
	}

	public long getAfter() {
		return this.after;
	}

	public long getLastSeq() {
		return this.lastSeq;
	}

}
