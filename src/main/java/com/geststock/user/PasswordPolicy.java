package com.geststock.user;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * Politique de mot de passe, appliquée avant tout hachage :
 * <ul>
 * <li>8 caractères minimum ;</li>
 * <li>72 octets maximum en UTF-8 (limite de bcrypt, au-delà le reste serait ignoré) ;</li>
 * <li>au moins une lettre et un chiffre ;</li>
 * <li>différent de l'identifiant.</li>
 * </ul>
 */
@Component
class PasswordPolicy {

	static final int MIN_LENGTH = 8;

	static final int MAX_BYTES = 72;

	void check(String password, String username) {
		List<String> problems = new ArrayList<>();
		if (password == null || password.length() < MIN_LENGTH) {
			problems.add("au moins " + MIN_LENGTH + " caractères");
		}
		if (password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			problems.add("au plus " + MAX_BYTES + " octets");
		}
		if (password != null && password.chars().noneMatch(Character::isLetter)) {
			problems.add("au moins une lettre");
		}
		if (password != null && password.chars().noneMatch(Character::isDigit)) {
			problems.add("au moins un chiffre");
		}
		if (password != null && username != null
				&& password.toLowerCase(Locale.ROOT).equals(username.toLowerCase(Locale.ROOT))) {
			problems.add("différent de l'identifiant");
		}
		if (!problems.isEmpty()) {
			throw new InvalidRequestException(ErrorCode.PASSWORD_POLICY_VIOLATION,
					"Mot de passe refusé : " + String.join(", ", problems) + ".");
		}
	}

}
