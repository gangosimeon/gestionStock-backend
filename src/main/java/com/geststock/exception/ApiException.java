package com.geststock.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Racine des exceptions métier et applicatives de GestStock. Le statut HTTP et le message
 * par défaut viennent du {@link ErrorCode} ; le message peut être précisé au cas par cas.
 *
 * <p>Le {@code message} est destiné à l'utilisateur final (français, sans détail technique).
 * Les {@code details} (facultatifs) complètent la réponse pour un client programmatique.
 */
public abstract class ApiException extends RuntimeException {

	private final ErrorCode code;

	private final transient Map<String, Object> details;

	protected ApiException(ErrorCode code, String message, Map<String, Object> details) {
		super(message != null ? message : code.defaultMessage());
		this.code = code;
		this.details = details != null ? Map.copyOf(details) : Map.of();
	}

	protected ApiException(ErrorCode code, String message) {
		this(code, message, null);
	}

	public ErrorCode getCode() {
		return this.code;
	}

	public HttpStatus getStatus() {
		return this.code.status();
	}

	public Map<String, Object> getDetails() {
		return this.details;
	}

}
