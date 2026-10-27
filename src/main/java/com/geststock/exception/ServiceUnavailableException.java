package com.geststock.exception;

/**
 * Service momentanément indisponible (503) pour une raison connue et temporaire (ex. base de
 * l'entreprise en cours de mise à jour) : le client peut réessayer plus tard.
 */
public class ServiceUnavailableException extends ApiException {

	public ServiceUnavailableException(ErrorCode code) {
		super(code, code.defaultMessage());
	}

}
