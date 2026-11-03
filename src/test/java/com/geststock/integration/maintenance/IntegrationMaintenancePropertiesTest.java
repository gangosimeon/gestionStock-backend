package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * Propriétés de la maintenance (G20-D1, D3, D12, D14) : empreintes SHA-256 hexadécimales (casse indifférente), vides
 * traitées comme absentes, jamais citées dans un message d'erreur ; budget positif, durée du bail égale à deux fois le
 * budget ; limite d'appels et fenêtre bornées.
 */
class IntegrationMaintenancePropertiesTest {

	private static final String SHA256 = "aa30cfbcb57ceb7df60ec3ed045c6fded70fb3635ca26f72edd1018ecd4bc112";

	@Test
	void defaultsAreDisabledWithATwentyFiveSecondBudget() {
		IntegrationMaintenanceProperties properties = new IntegrationMaintenanceProperties(false, null, null, null, 6, null);
		assertThat(properties.enabled()).isFalse();
		assertThat(properties.budget()).isEqualTo(Duration.ofSeconds(25));
		assertThat(properties.leaseTtl()).isEqualTo(Duration.ofSeconds(50));
		assertThat(properties.window()).isEqualTo(Duration.ofMinutes(1));
		assertThat(properties.fingerprints()).isEmpty();
		assertThat(properties.problems()).isEmpty();
	}

	@Test
	void fingerprintsAreHexadecimalSha256InAnyCaseAndBlankMeansAbsent() {
		IntegrationMaintenanceProperties properties = new IntegrationMaintenanceProperties(true, SHA256.toUpperCase(), " ",
				Duration.ofSeconds(10), 6, Duration.ofMinutes(1));
		assertThat(properties.tokenSha256()).isEqualTo(SHA256);
		assertThat(properties.previousTokenSha256()).isNull();
		assertThat(properties.fingerprints()).hasSize(1).allSatisfy(fingerprint -> assertThat(fingerprint).hasSize(32));
		assertThat(properties.leaseTtl()).isEqualTo(Duration.ofSeconds(20));
		assertThat(properties.problems()).isEmpty();
		assertThat(new IntegrationMaintenanceProperties(true, null, null, null, 6, null).problems())
			.singleElement().asString().contains("GESTSTOCK_MAINTENANCE_TOKEN_SHA256");
	}

	@Test
	void aMalformedFingerprintIsRejectedWithoutQuotingIt() {
		for (String value : new String[] { "secret-colle-par-erreur", SHA256.substring(2), SHA256.replace('a', 'g') }) {
			assertThatIllegalArgumentException()
				.isThrownBy(() -> new IntegrationMaintenanceProperties(true, value, null, null, 6, null))
				.withMessageContaining("token-sha256").withMessageNotContaining(value);
			assertThatIllegalArgumentException()
				.isThrownBy(() -> new IntegrationMaintenanceProperties(true, SHA256, value, null, 6, null))
				.withMessageContaining("previous-token-sha256").withMessageNotContaining(value);
		}
	}

	@Test
	void budgetCallsAndWindowAreBounded() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new IntegrationMaintenanceProperties(false, null, null, Duration.ZERO, 6, null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new IntegrationMaintenanceProperties(false, null, null, null, 0, null));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new IntegrationMaintenanceProperties(false, null, null, null, 6, Duration.ofHours(2)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new IntegrationMaintenanceProperties(false, null, null, null, 6, Duration.ofMillis(500)));
	}

	@Test
	void toStringNeverShowsAFingerprint() {
		assertThat(new IntegrationMaintenanceProperties(true, SHA256, SHA256.replace('a', 'b'), null, 6, null).toString())
			.doesNotContain(SHA256).doesNotContain(SHA256.replace('a', 'b'));
	}

}
