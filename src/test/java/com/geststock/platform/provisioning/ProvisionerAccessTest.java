package com.geststock.platform.provisioning;

import org.junit.jupiter.api.Test;

import com.geststock.platform.PlatformProperties;
import com.geststock.platform.company.CompanyDirectory;
import com.geststock.tenancy.LegacyDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Diagnostic d'un refus d'authentification du compte de provisionnement : jamais le mot de passe. */
class ProvisionerAccessTest {

	@Test
	void theHintNamesTheSourceWithoutRevealingThePassword() {
		String hint = access("Secret2026").passwordHint();

		assertThat(hint).contains("GESTSTOCK_PROVISIONER_DB_*").doesNotContain("Secret2026");
	}

	@Test
	void surroundingSpacesAndEmptyPasswordsAreReported() {
		assertThat(access(" Secret2026 ").passwordHint()).contains("espace").doesNotContain("Secret2026");
		assertThat(access("").passwordHint()).contains("mot de passe vide");
	}

	private static ProvisionerAccess access(String password) {
		PlatformProperties properties = new PlatformProperties(null,
				new PlatformProperties.Datasource("jdbc:postgresql://db:5432/geststock", "geststock_provisioner", password, 5),
				null, null, null);
		return new ProvisionerAccess(properties, mock(LegacyDatabase.class), mock(CompanyDirectory.class));
	}

}
