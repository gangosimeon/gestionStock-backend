package com.geststock.platform.admin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * Politique renforcée du Super Admin : 12 caractères au moins (72 octets au plus, limite bcrypt), une
 * minuscule, une majuscule, un chiffre, différent de l'identifiant.
 */
final class PlatformPasswordPolicy {

	static final int MIN_LENGTH = 12;

	private PlatformPasswordPolicy() {
	}

	static void check(String password, String username) {
		List<String> problems = new ArrayList<>();
		if (password == null || password.length() < MIN_LENGTH) {
			problems.add("au moins " + MIN_LENGTH + " caractères");
		}
		if (password != null && password.getBytes(StandardCharsets.UTF_8).length > 72) {
			problems.add("au plus 72 octets");
		}
		if (password != null && password.chars().noneMatch(Character::isLowerCase)) {
			problems.add("une minuscule");
		}
		if (password != null && password.chars().noneMatch(Character::isUpperCase)) {
			problems.add("une majuscule");
		}
		if (password != null && password.chars().noneMatch(Character::isDigit)) {
			problems.add("un chiffre");
		}
		if (password != null && username != null && password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
			problems.add("sans l'identifiant");
		}
		if (!problems.isEmpty()) {
			throw new InvalidRequestException(ErrorCode.PASSWORD_POLICY_VIOLATION,
					"Mot de passe refusé : " + String.join(", ", problems) + ".");
		}
	}

}
