package com.geststock.integration;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Clés de signature des {@code pageToken} d'instantané ({@code geststock.integration.page-token.*}).
 *
 * <p>{@code keys} : identifiant de clé ({@code kid}) → secret en Base64, 32 octets au moins. {@code current-kid} :
 * la clé qui signe ; les autres clés listées servent seulement à vérifier. Valeurs de développement et de test
 * dans {@code application-dev.yml} et {@code application-test.yml} ; en production, par l'environnement (par
 * exemple {@code GESTSTOCK_INTEGRATION_PAGETOKEN_CURRENTKID} et
 * {@code GESTSTOCK_INTEGRATION_PAGETOKEN_KEYS_<KID>}), jamais dans un fichier versionné. Les messages d'erreur
 * nomment le {@code kid}, jamais le secret.
 *
 * @param currentKid identifiant de la clé courante
 * @param keys       secrets par identifiant de clé (Base64)
 */
@ConfigurationProperties(prefix = "geststock.integration.page-token")
public record PageTokenProperties(String currentKid, @DefaultValue Map<String, String> keys) {

	/** Longueur minimale d'un secret, en octets (HMAC-SHA256). */
	public static final int MIN_KEY_BYTES = 32;

	private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,32}");

	public PageTokenProperties {
		keys = keys != null ? Map.copyOf(keys) : Map.of();
	}

	/**
	 * Problèmes de configuration ; vide si une clé courante valide est définie et si toutes les clés listées sont
	 * valides. Aucun message ne contient de secret.
	 */
	public List<String> problems() {
		List<String> problems = new ArrayList<>();
		if (this.currentKid == null || this.currentKid.isBlank()) {
			problems.add("geststock.integration.page-token.current-kid est vide : une clé courante de signature des "
					+ "jetons d'instantané est requise.");
		}
		else if (!this.keys.containsKey(this.currentKid)) {
			problems.add("geststock.integration.page-token.keys ne contient pas la clé courante « " + this.currentKid
					+ " ».");
		}
		this.keys.forEach((kid, secret) -> problems.addAll(keyProblems(kid, secret)));
		return problems;
	}

	/** Secrets décodés, par identifiant de clé ; erreur explicite (sans secret) si une clé listée est invalide. */
	Map<String, byte[]> secrets() {
		Map<String, byte[]> secrets = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();
		this.keys.forEach((kid, secret) -> {
			List<String> keyProblems = keyProblems(kid, secret);
			if (keyProblems.isEmpty()) {
				secrets.put(kid, Base64.getDecoder().decode(secret.trim()));
			}
			problems.addAll(keyProblems);
		});
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Clés des jetons d'instantané invalides : " + String.join(" ; ", problems));
		}
		return secrets;
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
