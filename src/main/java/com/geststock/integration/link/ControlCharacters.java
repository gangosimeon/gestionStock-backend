package com.geststock.integration.link;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * Caractères de contrôle dans les valeurs reçues par les routes d'intégration (lot 2, étape G15) : U+0000 à U+001F et
 * U+007F sont refusés ; tabulation, saut de ligne et retour chariot sont permis dans les seuls motifs libres
 * ({@link FreeText}). Contrôle commun à toutes les entrées : formulaires OAuth ({@link OAuthController}), corps JSON
 * ({@link IntegrationBodyAdvice}), variables de chemin ({@link ControlCharacterPathCheck}) et identifiants du
 * client ({@link ClientAuthenticationFilter}). Chaque entrée refuse au format de sa route ; le message nomme le champ
 * (nom connu du code), jamais la valeur reçue.
 */
final class ControlCharacters {

	private ControlCharacters() {
	}

	/** Motif libre : tabulation et sauts de ligne permis. */
	@Target({ ElementType.RECORD_COMPONENT, ElementType.FIELD })
	@Retention(RetentionPolicy.RUNTIME)
	@interface FreeText {
	}

	/** Vrai si la valeur contient un caractère de contrôle refusé ; {@code freeText} : tabulation et sauts de ligne permis. */
	static boolean present(String value, boolean freeText) {
		if (value == null) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if ((c < 0x20 || c == 0x7F) && !(freeText && (c == '\t' || c == '\n' || c == '\r'))) {
				return true;
			}
		}
		return false;
	}

	/** Refus {@code 400 VALIDATION_FAILED} (format {@code ApiError}), le champ nommé sans sa valeur. */
	static InvalidRequestException refused(String field) {
		return new InvalidRequestException(ErrorCode.VALIDATION_FAILED, "Caractère de contrôle refusé : " + field + ".");
	}

}
