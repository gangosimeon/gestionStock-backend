package com.geststock.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

	private final PasswordPolicy policy = new PasswordPolicy();

	@Test
	void acceptsPasswordWithLettersAndDigits() {
		assertThatCode(() -> this.policy.check("Optique2026", "pierre.d")).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@CsvSource({
			"abc12,         au moins 8 caractères",
			"motdepasse,    au moins un chiffre",
			"12345678,      au moins une lettre",
			"Pierre.D1,     différent de l'identifiant" })
	void rejectsWeakPasswordsWithExplicitReason(String password, String reason) {
		String username = "pierre.d1";
		assertThatThrownBy(() -> this.policy.check(password, username))
			.isInstanceOf(InvalidRequestException.class)
			.hasMessageContaining(reason.trim())
			.extracting(ex -> ((InvalidRequestException) ex).getCode())
			.isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
	}

	@Test
	void rejectsPasswordLongerThanBcryptLimitInBytes() {
		// 37 caractères mais 73 octets en UTF-8 (« é » = 2 octets) : bcrypt ignorerait la fin.
		String password = "é".repeat(36) + "1";
		assertThatThrownBy(() -> this.policy.check(password, "pierre.d"))
			.hasMessageContaining("au plus 72 octets");
	}

	@Test
	void rejectsMissingPassword() {
		assertThatThrownBy(() -> this.policy.check(null, "pierre.d"))
			.hasMessageContaining("au moins 8 caractères");
	}

}
