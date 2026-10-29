package com.geststock.integration.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Liaisons avec les boutiques en ligne ({@code geststock.integration.links.*}, lot 2).
 *
 * <p>{@code enabled} : interrupteur général des liaisons (faux par défaut ; vrai en test). {@code token} : clés de
 * signature des jetons d'accès des liaisons, sur le modèle des clés des {@code pageToken} : identifiant de clé
 * ({@code kid}) → secret en Base64, 32 octets au moins ; {@code current-kid} signe, les autres clés listées servent
 * seulement à vérifier (rotation). Clés distinctes du secret des jetons utilisateurs ({@code GESTSTOCK_JWT_SECRET}).
 * Valeurs de développement et de test dans {@code application-dev.yml} et {@code application-test.yml} ; en
 * production, par l'environnement ({@code GESTSTOCK_INTEGRATION_LINKS_TOKEN_CURRENTKID},
 * {@code GESTSTOCK_INTEGRATION_LINKS_TOKEN_KEYS_<KID>}), jamais dans un fichier versionné. Les messages nomment le
 * {@code kid}, jamais le secret.
 *
 * @param enabled liaisons activées
 * @param token   clés et tolérance d'horloge des jetons de liaison
 */
@ConfigurationProperties(prefix = "geststock.integration.links")
public record IntegrationLinkProperties(@DefaultValue("false") boolean enabled, @DefaultValue Token token) {

	/**
	 * @param currentKid identifiant de la clé courante (signature)
	 * @param keys       secrets par identifiant de clé (Base64)
	 * @param clockSkew  tolérance d'horloge à la vérification de {@code exp} et {@code iat}
	 */
	public record Token(String currentKid, @DefaultValue Map<String, String> keys,
			@DefaultValue("30s") Duration clockSkew) {

		/** Longueur minimale d'un secret, en octets (HMAC-SHA256). */
		public static final int MIN_KEY_BYTES = 32;

		private static final String PREFIX = "geststock.integration.links.token";

		private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,32}");

		public Token {
			keys = keys != null ? Map.copyOf(keys) : Map.of();
			clockSkew = clockSkew != null ? clockSkew : Duration.ofSeconds(30);
		}

		/**
		 * Problèmes de configuration ; vide si une clé courante valide est définie et si toutes les clés listées sont
		 * valides. Aucun message ne contient de secret.
		 */
		public List<String> problems() {
			List<String> problems = new ArrayList<>();
			if (this.currentKid == null || this.currentKid.isBlank()) {
				problems.add(PREFIX + ".current-kid est vide : une clé courante de signature des jetons de liaison est requise.");
			}
			else if (!this.keys.containsKey(this.currentKid)) {
				problems.add(PREFIX + ".keys ne contient pas la clé courante « " + this.currentKid + " ».");
			}
			this.keys.forEach((kid, secret) -> problems.addAll(keyProblems(kid, secret)));
			if (this.clockSkew.isNegative() || this.clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
				problems.add(PREFIX + ".clock-skew doit être compris entre 0 et 5 minutes.");
			}
			return problems;
		}

		/** Secrets décodés, par identifiant de clé ; erreur explicite (sans secret) si une clé listée est invalide. */
		Map<String, byte[]> secrets() {
			Map<String, byte[]> secrets = new LinkedHashMap<>();
			List<String> problems = new ArrayList<>();
			this.keys.forEach((kid, secret) -> {
				List<String> keyProblems = keyProblems(kid, secret);
				if (keyProblems.isEmpty()) {
					secrets.put(kid, decode(secret));
				}
				problems.addAll(keyProblems);
			});
			if (!problems.isEmpty()) {
				throw new IllegalStateException("Clés des jetons de liaison invalides : " + String.join(" ; ", problems));
			}
			return secrets;
		}

		/**
		 * Identifiants des clés listées qui reprennent {@code other} (secret brut, par exemple celui des jetons
		 * utilisateurs) : même contenu une fois décodée, ou même valeur textuelle.
		 */
		public List<String> keysEqualTo(String other) {
			List<String> equal = new ArrayList<>();
			byte[] otherBytes = other.getBytes(StandardCharsets.UTF_8);
			this.keys.forEach((kid, secret) -> {
				boolean sameText = secret != null && MessageDigest.isEqual(secret.trim().getBytes(StandardCharsets.UTF_8),
						other.trim().getBytes(StandardCharsets.UTF_8));
				boolean sameBytes = keyProblems(kid, secret).isEmpty() && MessageDigest.isEqual(decode(secret), otherBytes);
				if (sameText || sameBytes) {
					equal.add(kid);
				}
			});
			return equal;
		}

		private static byte[] decode(String secret) {
			return Base64.getDecoder().decode(secret.trim());
		}

		private static List<String> keyProblems(String kid, String secret) {
			List<String> problems = new ArrayList<>();
			if (!KID.matcher(kid).matches()) {
				problems.add("identifiant de clé « " + kid + " » invalide (1 à 32 caractères : lettres, chiffres, . _ -)");
			}
			byte[] decoded;
			try {
				decoded = Base64.getDecoder().decode(secret == null ? "" : secret.trim());
			}
			catch (IllegalArgumentException ex) {
				problems.add("clé « " + kid + " » : la valeur n'est pas en Base64");
				return problems;
			}
			if (decoded.length < MIN_KEY_BYTES) {
				problems.add("clé « " + kid + " » : " + MIN_KEY_BYTES + " octets au moins après décodage Base64");
			}
			return problems;
		}

	}

}
