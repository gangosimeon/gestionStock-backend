package com.geststock.exception;

/**
 * Échec d'authentification renvoyé par un endpoint public (connexion, renouvellement de
 * session) : 401 avec un code précis ({@code INVALID_CREDENTIALS}, {@code ACCOUNT_LOCKED}...).
 *
 * <p>Distinct des {@code AuthenticationException} de Spring Security, qui déclenchent
 * l'entry point (requête protégée sans jeton valide).
 */
public class UnauthorizedException extends ApiException {

	public UnauthorizedException(ErrorCode code) {
		super(code, code.defaultMessage());
	}

}
