package com.geststock.platform;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Mots de passe des rôles d'exécution : chiffrés AES-256-GCM, illisibles avec une autre clé ou altérés. */
class CredentialCipherTest {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

	private static final String OTHER_KEY = Base64.getEncoder().encodeToString("autre-cle-de-32-octets-exactemen".getBytes());

	private static CredentialCipher cipher(String key) {
		return new CredentialCipher(new PlatformProperties(null, null, key, null, null));
	}

	@Test
	void encryptsWithARandomIvAndDecryptsBack() {
		CredentialCipher cipher = cipher(KEY);
		String first = cipher.encrypt("Secret-1234");
		String second = cipher.encrypt("Secret-1234");
		assertThat(first).startsWith("v1:").doesNotContain("Secret").isNotEqualTo(second);
		assertThat(cipher.decrypt(first)).isEqualTo("Secret-1234");
	}

	@Test
	void anotherKeyOrAlteredDataIsRefusedWithoutDetail() {
		String stored = cipher(KEY).encrypt("Secret-1234");
		assertThatThrownBy(() -> cipher(OTHER_KEY).decrypt(stored)).hasMessageNotContaining("Secret-1234");
		String altered = stored.substring(0, stored.length() - 2) + (stored.endsWith("A") ? "B" : "A") + "=";
		assertThatThrownBy(() -> cipher(KEY).decrypt(altered)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aMissingOrMalformedKeyIsExplicit() {
		assertThat(cipher(null).isConfigured()).isFalse();
		assertThatThrownBy(() -> cipher(null).encrypt("x")).hasMessageContaining("GESTSTOCK_TENANT_CREDENTIALS_KEY");
		assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16]))).hasMessageContaining("32 octets");
	}

}
