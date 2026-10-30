package com.geststock.integration.link;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

/**
 * Refus du consentement ou de la gestion des liaisons (G10), sous forme d'erreur d'API ({@code ApiError}) : codes
 * explicites pour l'écran, jamais de détail sur une autre entreprise.
 */
class LinkConsentException extends ApiException {

	LinkConsentException(ErrorCode code) {
		super(code, null);
	}

	/** Refus métier du registre des liaisons (G3), traduit en code d'API ; jamais une erreur 500. */
	static LinkConsentException of(LinkException ex) {
		return new LinkConsentException(switch (ex.reason()) {
			case REQUEST_NOT_FOUND -> ErrorCode.AUTHORIZATION_REQUEST_NOT_FOUND;
			case REQUEST_NOT_APPROVED -> ErrorCode.AUTHORIZATION_REQUEST_ALREADY_DECIDED;
			case CLIENT_DISABLED, SCOPE_NOT_ALLOWED -> ErrorCode.LINK_CLIENT_UNAVAILABLE;
			case COMPANY_NOT_FOUND, COMPANY_NOT_ACTIVE -> ErrorCode.COMPANY_UNAVAILABLE;
			case CURRENCY_UNSUPPORTED -> ErrorCode.LINK_CURRENCY_UNSUPPORTED;
			case SCOPE_NOT_REQUESTED -> ErrorCode.LINK_SCOPE_NOT_REQUESTED;
			case INVALID_ARGUMENT -> ErrorCode.INVALID_PARAMETER;
			case SHOP_ALREADY_LINKED -> ErrorCode.SHOP_ALREADY_LINKED;
			case LINK_NOT_FOUND -> ErrorCode.LINK_NOT_FOUND;
			case INVALID_TRANSITION -> ErrorCode.LINK_TRANSITION_INVALID;
		});
	}

}
