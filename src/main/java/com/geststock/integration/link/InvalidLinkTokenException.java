package com.geststock.integration.link;

/**
 * Jeton de liaison refusé. Le message nomme seulement la raison : jamais le jeton, ses claims ni une clé.
 */
public class InvalidLinkTokenException extends RuntimeException {

	/** Raison du refus, dans l'ordre des contrôles. */
	public enum Reason {

		/** Jeton absent, illisible, ou qui n'est pas un JWS compact. */
		MALFORMED("jeton illisible"),
		/** Algorithme autre que HS256 (dont {@code none}). */
		ALGORITHM("algorithme non accepté"),
		/** En-tête {@code typ} absent ou différent de {@code at+jwt}. */
		HEADER_TYPE("type d'en-tête incorrect"),
		/** {@code kid} absent ou inconnu (clé retirée de la liste comprise). */
		UNKNOWN_KEY("clé inconnue"),
		/** Signature invalide. */
		SIGNATURE("signature invalide"),
		/** Claim obligatoire absent. */
		MISSING_CLAIM("claim obligatoire absent"),
		/** Claim présent mais mal formé ({@code sub} non UUID, {@code scope} vide…). */
		INVALID_CLAIM("claim mal formé"),
		/** Émetteur incorrect. */
		ISSUER("émetteur incorrect"),
		/** Audience incorrecte. */
		AUDIENCE("audience incorrecte"),
		/** Claim {@code typ} différent de {@code integration}. */
		TOKEN_TYPE("type de jeton incorrect"),
		/** Durée de vie supérieure à 15 minutes. */
		LIFETIME("durée de vie excessive"),
		/** Émis dans le futur, au-delà de la tolérance d'horloge. */
		NOT_YET_VALID("jeton émis dans le futur"),
		/** Expiré, au-delà de la tolérance d'horloge. */
		EXPIRED("jeton expiré");

		private final String label;

		Reason(String label) {
			this.label = label;
		}

	}

	private final Reason reason;

	public InvalidLinkTokenException(Reason reason) {
		super("Jeton de liaison refusé : " + reason.label);
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

}
