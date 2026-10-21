package com.geststock.platform;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Chiffrement AES-256-GCM des mots de passe des rôles d'exécution stockés en base plateforme. Format :
 * {@code v1:<Base64(iv ‖ texte chiffré ‖ tag)>}. Sans clé configurée, le chiffrement est refusé (les
 * bases dédiées ne peuvent alors pas être créées) ; la clé n'est jamais journalisée.
 */
@Component
public class CredentialCipher {

	private static final String PREFIX = "v1:";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;

	private final SecureRandom random = new SecureRandom();

	CredentialCipher(PlatformProperties properties) {
		this.key = parseKey(properties.credentialsKey());
	}

	static SecretKeySpec parseKey(String base64) {
		if (base64 == null || base64.isBlank()) {
			return null;
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(base64.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("GESTSTOCK_TENANT_CREDENTIALS_KEY : Base64 invalide.");
		}
		if (bytes.length != 32) {
			throw new IllegalStateException("GESTSTOCK_TENANT_CREDENTIALS_KEY : 32 octets attendus (AES-256).");
		}
		return new SecretKeySpec(bytes, "AES");
	}

	public boolean isConfigured() {
		return this.key != null;
	}

	public String encrypt(String plain) {
		requireKey();
		try {
			byte[] iv = new byte[IV_BYTES];
			this.random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, out, 0, iv.length);
			System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
			return PREFIX + Base64.getEncoder().encodeToString(out);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Chiffrement impossible", ex);
		}
	}

	public String decrypt(String stored) {
		requireKey();
		if (stored == null || !stored.startsWith(PREFIX)) {
			throw new IllegalStateException("Secret chiffré illisible (format inconnu).");
		}
		try {
			byte[] data = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, this.key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
			return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			// Clé changée ou donnée altérée : jamais de détail cryptographique dans le message.
			throw new IllegalStateException("Secret chiffré illisible (clé différente ou donnée altérée).");
		}
	}

	private void requireKey() {
		if (this.key == null) {
			throw new IllegalStateException("GESTSTOCK_TENANT_CREDENTIALS_KEY absente : bases dédiées indisponibles.");
		}
	}

}
