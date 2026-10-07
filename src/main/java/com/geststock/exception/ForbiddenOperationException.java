package com.geststock.exception;

/**
 * Opération interdite pour l'utilisateur authentifié (403) pour une raison métier
 * (ex. accès à un dépôt non autorisé, suppression du dernier administrateur). Les refus
 * liés aux permissions passent par Spring Security ({@code AccessDeniedException}).
 */
public class ForbiddenOperationException extends ApiException {

	public ForbiddenOperationException(String message) {
		super(ErrorCode.ACCESS_DENIED, message);
	}

	public ForbiddenOperationException(ErrorCode code, String message) {
		super(code, message);
	}

}
