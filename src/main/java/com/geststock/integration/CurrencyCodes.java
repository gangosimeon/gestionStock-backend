package com.geststock.integration;

import java.util.Locale;
import java.util.Map;

/**
 * Devise de l'entreprise en code ISO 4217 (décision v11-D6), normalisée depuis {@code platform.company.currency} :
 * {@code FCFA}, {@code F CFA} et {@code XOF} (casse et espaces indifférents) donnent {@code XOF}. Toute autre valeur
 * est refusée explicitement, jamais devinée.
 */
public final class CurrencyCodes {

	private static final Map<String, String> ISO_4217 = Map.of("FCFA", "XOF", "F CFA", "XOF", "XOF", "XOF");

	private CurrencyCodes() {
	}

	/**
	 * @throws UnsupportedCurrencyException valeur absente ou inconnue
	 */
	public static String iso4217(String stored) {
		String normalized = stored == null ? "" : stored.trim().replaceAll("\s+", " ").toUpperCase(Locale.ROOT);
		String code = ISO_4217.get(normalized);
		if (code == null) {
			throw new UnsupportedCurrencyException(stored);
		}
		return code;
	}

}
