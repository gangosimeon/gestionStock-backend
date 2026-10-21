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
	void multiCompanyProductionNeedsItsOwnPlatformDatabaseAndNoDefaultCompany() {
		String key = "dGVzdC1vbmx5LWtleS0zMi1ieXRlcy1sb25nLTAwMDA=";
		assertThat(ProductionSafetyCheck.tenancyProblems(null, "jdbc:postgresql://db/geststock_platform",
				"jdbc:postgresql://db/postgres", key, "Un-Mot2Passe-Solide")).isEmpty();
		// Entreprise par défaut : une base serait visée sans authentification.
		assertThat(ProductionSafetyCheck.tenancyProblems("principale", "jdbc:postgresql://db/p", "jdbc:postgresql://db/a", key,
				null)).singleElement().asString().contains("GESTSTOCK_DEFAULT_COMPANY");
		assertThat(ProductionSafetyCheck.tenancyProblems(null, null, null, null, "court")).hasSize(4);
	}

	@Test
	void aShortAdminPasswordAndFlywayCleanAreRefused() {
		assertThat(ProductionSafetyCheck.problems(List.of("https://a.example"), false, "court", false)).hasSize(2);
	}

}
