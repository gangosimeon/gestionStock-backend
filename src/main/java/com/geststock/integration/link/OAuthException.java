package com.geststock.integration.link;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Erreur OAuth des routes du client ({@code /oauth/par}, {@code /oauth/token}) : corps {@code OAuthError} du contrat
 * ({@code error}, {@code error_description}), et {@code code} pour une liaison révoquée ({@code LINK_REVOKED}). La
 * description n'expose jamais de secret, de code ni d'information sur une entreprise.
 */
class OAuthException extends RuntimeException {

	private final HttpStatus status;

	private final String error;

	private final String code;

	private OAuthException(HttpStatus status, String error, String description, String code) {
		super(description);
		this.status = status;
		this.error = error;
		this.code = code;
	}

	static OAuthException invalidRequest(String description) {
		return new OAuthException(HttpStatus.BAD_REQUEST, "invalid_request", description, null);
	}

	static OAuthException invalidScope(String description) {
		return new OAuthException(HttpStatus.BAD_REQUEST, "invalid_scope", description, null);
	}

	static OAuthException invalidGrant() {
		return new OAuthException(HttpStatus.BAD_REQUEST, "invalid_grant", "Autorisation invalide, expirée ou déjà utilisée.", null);
	}

	/** Service momentanément indisponible (base de l'entreprise) : rien n'est consommé, l'appel peut être refait. */
	static OAuthException temporarilyUnavailable() {
		return new OAuthException(HttpStatus.SERVICE_UNAVAILABLE, "temporarily_unavailable",
				"Service momentanément indisponible : réessayez.", null);
	}

	static OAuthException unsupportedGrantType() {
		return new OAuthException(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "Type d'autorisation non pris en charge.", null);
	}

	/** Liaison révoquée (C2) : réponse 401, propre à un client déjà authentifié. */
	/** 400 et non 401 : au point d'émission des jetons, 401 est réservé à invalid_client (RFC 6749 § 5.2). */
	static OAuthException linkRevoked() {
		return new OAuthException(HttpStatus.BAD_REQUEST, "invalid_grant", "Liaison révoquée.", "LINK_REVOKED");
	}

	HttpStatus status() {
		return this.status;
	}

	Map<String, Object> body() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", this.error);
		body.put("error_description", getMessage());
		if (this.code != null) {
			body.put("code", this.code);
		}
		return body;
	}

}
