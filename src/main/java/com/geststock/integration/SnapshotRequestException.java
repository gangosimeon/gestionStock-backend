package com.geststock.integration;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * {@code limit} de l'instantané hors de 1..500 : {@code VALIDATION_FAILED} (400), même code que la validation des
 * paramètres de requête par Bean Validation dans cette API.
 */
public class SnapshotRequestException extends InvalidRequestException {

	public SnapshotRequestException(String message) {
		super(ErrorCode.VALIDATION_FAILED, message);
	}

}
