package com.geststock.exception;

/** Ressource inexistante (404). */
public class NotFoundException extends ApiException {

	public NotFoundException(String message) {
		super(ErrorCode.RESOURCE_NOT_FOUND, message);
	}

	public NotFoundException(ErrorCode code, String message) {
		super(code, message);
	}

}
