package com.geststock.integration;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

/**
 * Curseur en avance : {@code after} dépasse le dernier {@code seq} attribué. Cas anormal (curseur d'une
 * autre entreprise, état du flux réinitialisé ou corrompu) : jamais de page vide silencieuse, le
 * consommateur continuerait d'attendre des {@code seq} qui ne viendront qu'après un saut. Code du contrat :
 * {@code CURSOR_AHEAD} (409) ; le consommateur ne reprend pas avec ce curseur et vérifie la liaison.
 */
public class CursorAheadException extends ApiException {

	private final long after;

	private final long lastSeq;

	public CursorAheadException(long after, long lastSeq) {
		super(ErrorCode.CURSOR_AHEAD, null);
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
