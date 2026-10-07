package com.geststock.exception;

/** Requête incohérente détectée par le code (400), ex. plage de dates inversée. */
public class InvalidRequestException extends ApiException {

	public InvalidRequestException(String message) {
		super(ErrorCode.INVALID_PARAMETER, message);
	}

	public InvalidRequestException(ErrorCode code, String message) {
		super(code, message);
	}

}
