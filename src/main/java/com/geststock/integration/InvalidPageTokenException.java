package com.geststock.integration;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

/**
 * {@code pageToken} illisible, altéré, signé par une clé inconnue ou émis pour une autre liaison : 400
 * {@code INVALID_PAGE_TOKEN}. Le message ne donne jamais la cause précise ni aucune clé.
 */
public class InvalidPageTokenException extends ApiException {

	public InvalidPageTokenException() {
		super(ErrorCode.INVALID_PAGE_TOKEN, null);
	}

}
