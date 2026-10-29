package com.geststock.integration;

import com.geststock.exception.InvalidRequestException;

/**
 * Paramètre de lecture du flux hors bornes ({@code after < 0}, {@code limit} hors de 1..500). Le contrat ne
 * fixe pas de code dédié (400, validation du schéma) : code générique {@code INVALID_PARAMETER}.
 */
public class ChangeFeedRequestException extends InvalidRequestException {

	public ChangeFeedRequestException(String message) {
		super(message);
	}

}
