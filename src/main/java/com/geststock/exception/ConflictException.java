package com.geststock.exception;

/** Conflit d'état ou d'unicité (409). */
public class ConflictException extends ApiException {

	public ConflictException(String message) {
		super(ErrorCode.CONFLICT, message);
	}

	public ConflictException(ErrorCode code, String message) {
		super(code, message);
	}

}
