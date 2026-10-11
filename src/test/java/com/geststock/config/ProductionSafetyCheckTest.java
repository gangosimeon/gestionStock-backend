package com.geststock.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Garde-fous du profil prod : configurations acceptées et refusées. */
class ProductionSafetyCheckTest {

	@Test
	void aHttpsOriginAndARobustAdminPasswordAreAccepted() {
		assertThat(ProductionSafetyCheck.problems(List.of("https://geststock.example.com"), false, "Un-Mot2Passe-Solide",
				true)).isEmpty();
		// Sans administrateur initial (déjà créé) : rien à vérifier.
		assertThat(ProductionSafetyCheck.problems(List.of("https://geststock.example.com"), false, null, true)).isEmpty();
	}

	@Test
	void missingWildcardOrPlainHttpOriginsAreRefused() {
		assertThat(ProductionSafetyCheck.problems(List.of(), false, null, true)).singleElement()
			.asString()
			.contains("GESTSTOCK_CORS_ALLOWED_ORIGINS");
		assertThat(ProductionSafetyCheck.problems(List.of("*"), false, null, true)).singleElement()
			.asString()
			.contains("joker");
		assertThat(ProductionSafetyCheck.problems(List.of("http://geststock.example.com"), false, null, true))
			.singleElement()
			.asString()
			.contains("https://");
		// Recette locale explicite : http accepté, le joker reste interdit.
		assertThat(ProductionSafetyCheck.problems(List.of("http://localhost:8088"), true, null, true)).isEmpty();
		assertThat(ProductionSafetyCheck.problems(List.of("http://*"), true, null, true)).hasSize(1);
	}

	@Test
	void aShortAdminPasswordAndFlywayCleanAreRefused() {
		assertThat(ProductionSafetyCheck.problems(List.of("https://a.example"), false, "court", false)).hasSize(2);
	}

}
