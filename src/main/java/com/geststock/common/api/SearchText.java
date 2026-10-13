package com.geststock.common.api;

import java.util.Locale;

import org.springframework.util.StringUtils;

/**
 * Recherche texte des listes ({@code q}) : motif LIKE insensible à la casse. Les jokers saisis
 * ({@code %}, {@code _}) sont cherchés tels quels (caractère d'échappement {@code \}).
 */
public final class SearchText {

	/** Motif qui correspond à tout (requêtes JPQL écrites avec un motif toujours renseigné). */
	public static final String ANY = "%";

	private SearchText() {
	}

	public static boolean present(String text) {
		return StringUtils.hasText(text);
	}

	/** {@code %texte%} en minuscules, échappé ; {@link #ANY} sans texte. */
	public static String pattern(String text) {
		if (!present(text)) {
			return ANY;
		}
		String escaped = text.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

}
