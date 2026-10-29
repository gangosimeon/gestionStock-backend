package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Devise de l'entreprise en code ISO 4217 (décision v11-D6) : FCFA, F CFA et XOF donnent XOF ; rien n'est deviné. */
class CurrencyCodesTest {

	@Test
	void theLocalLabelsOfTheCfaFrancGiveXof() {
		for (String stored : new String[] { "FCFA", "F CFA", "XOF", " xof ", "f  cfa", "Fcfa" }) {
			assertThat(CurrencyCodes.iso4217(stored)).as(stored).isEqualTo("XOF");
		}
	}

	@Test
	void anUnknownCurrencyIsRefusedExplicitly() {
		for (String stored : new String[] { "EUR", "CFA", "F.CFA", "", null }) {
			assertThatThrownBy(() -> CurrencyCodes.iso4217(stored)).as(String.valueOf(stored))
				.isInstanceOf(UnsupportedCurrencyException.class)
				.hasMessageContaining("ISO 4217")
				.hasMessageContaining("FCFA, F CFA, XOF");
		}
	}

}
