package com.geststock.integration;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

/**
 * Curseur trop ancien (décision v9-D1) : {@code after} est strictement inférieur au repère de purge, des
 * événements postérieurs ne sont plus disponibles. Le consommateur abandonne ce curseur, relance un
 * instantané complet puis reprend avec {@code after = asOfSeq}. Code du contrat : {@code CURSOR_EXPIRED} (410).
 */
public class CursorExpiredException extends ApiException {

	private final long after;

	private final long purgedThroughSeq;

	public CursorExpiredException(long after, long purgedThroughSeq) {
		super(ErrorCode.CURSOR_EXPIRED, null);
		this.after = after;
		this.purgedThroughSeq = purgedThroughSeq;
	}

	public long getAfter() {
		return this.after;
	}

	public long getPurgedThroughSeq() {
		return this.purgedThroughSeq;
	}

}
