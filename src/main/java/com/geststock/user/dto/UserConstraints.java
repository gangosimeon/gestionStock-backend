package com.geststock.user.dto;

/** Limites de validation, alignées sur les colonnes et contraintes de {@code app_user}. */
public final class UserConstraints {

	/** Identique à {@code ck_app_user_username_format}. */
	public static final String USERNAME_PATTERN = "^[A-Za-z0-9._-]{3,50}$";

	public static final String USERNAME_MESSAGE =
			"3 à 50 caractères : lettres sans accent, chiffres, point, tiret ou souligné";

	public static final int NAME_MAX = 75;

	public static final int FULL_NAME_MAX = 151;

	public static final int EMAIL_MAX = 254;

	public static final int PHONE_MAX = 30;

	private UserConstraints() {
	}

	static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
