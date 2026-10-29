package com.geststock.integration;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * Paramètre de lecture du flux hors bornes ({@code after < 0}, {@code limit} hors de 1..500). Même code que la
 * validation des paramètres de requête par Bean Validation dans cette API : {@code VALIDATION_FAILED} (400).
 *
 * <p>{@code GET /catalog/changes} renvoie deux codes 400 distincts (décision 3) :
 * <ul>
 * <li>{@code VALIDATION_FAILED} : valeur numérique hors bornes ({@code @Min}/{@code @Max} de la couche HTTP,
 * ou cette exception si le service est appelé directement) ;</li>
 * <li>{@code INVALID_PARAMETER} : paramètre non numérique, ou {@code after} absent, refusé par la couche HTTP
 * avant tout appel du service ({@code GlobalExceptionHandler}).</li>
 * </ul>
 */
public class ChangeFeedRequestException extends InvalidRequestException {

	public ChangeFeedRequestException(String message) {
		super(ErrorCode.VALIDATION_FAILED, message);
	}

}
