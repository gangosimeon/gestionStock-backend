package com.geststock.platform.provisioning;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Vérificateur SCRAM-SHA-256 au format PostgreSQL ({@code SCRAM-SHA-256$<itérations>:<sel>$<StoredKey>:<ServerKey>},
 * RFC 5802 / 7677). Envoyé tel quel dans {@code CREATE ROLE … PASSWORD '…'}, il est stocké sans
 * transformation : le mot de passe en clair ne transite jamais vers le serveur ni dans ses journaux.
 */
final class ScramSha256 {

	private static final int ITERATIONS = 4096;

	private static final SecureRandom RANDOM = new SecureRandom();

	private ScramSha256() {
	}

	static String verifier(String password) {
		byte[] salt = new byte[16];
		RANDOM.nextBytes(salt);
		return verifier(password, salt, ITERATIONS);
	}

	static String verifier(String password, byte[] salt, int iterations) {
		try {
			SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
			byte[] salted = factory.generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
			byte[] clientKey = hmac(salted, "Client Key");
			byte[] storedKey = MessageDigest.getInstance("SHA-256").digest(clientKey);
			byte[] serverKey = hmac(salted, "Server Key");
			Base64.Encoder b64 = Base64.getEncoder();
			return "SCRAM-SHA-256$" + iterations + ":" + b64.encodeToString(salt) + "$" + b64.encodeToString(storedKey) + ":"
					+ b64.encodeToString(serverKey);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("SCRAM-SHA-256 indisponible", ex);
		}
	}

	private static byte[] hmac(byte[] key, String message) throws GeneralSecurityException {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		return mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
	}

}
