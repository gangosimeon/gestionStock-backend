package com.geststock.security;

import org.springframework.security.core.AuthenticationException;

import com.geststock.exception.ErrorCode;

/**
 * Jeton valide, mais entreprise suspendue ou indisponible : l'entry point répond 403 avec ce code
 * (le front affiche alors un écran explicatif plutôt qu'une demande de reconnexion).
 */
public class CompanyAccessException extends AuthenticationException {

	private final ErrorCode code;

	public CompanyAccessException(ErrorCode code) {
		super(code.defaultMessage());
		this.code = code;
	}

	public ErrorCode code() {
		return this.code;
	}

}
