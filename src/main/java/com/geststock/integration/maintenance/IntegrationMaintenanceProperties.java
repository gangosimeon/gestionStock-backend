package com.geststock.integration.maintenance;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.geststock.integration.link.RateLimiter;

/**
 * Maintenance de l'intégration ({@code geststock.integration.maintenance.*}, lot 3, G20 ; plan § 33), livrée
 * <b>désactivée</b> : route interne {@code POST /internal/integration/maintenance} appelée par le cron.
 *
 * @param enabled             route servie ; sinon toute requête {@code /internal/**} reçoit {@code 404} (G20-D2)
 * @param tokenSha256         empreinte SHA-256 (hexadécimal) du secret du cron ; le secret lui-même n'est connu que du
 *                            cron (G20-D1). Obligatoire en production quand la maintenance est activée
 * @param previousTokenSha256 empreinte acceptée en plus pendant une rotation du secret (facultative)
 * @param budget              durée de travail d'un passage (G20-D3, valeur de départ ; à fixer selon le délai HTTP de
 *                            l'hébergeur) ; le bail dure deux fois le budget (G20-D12)
 * @param callsPerWindow      appels <b>authentifiés</b> admis par fenêtre, tous cron confondus (G20-D14) ; un échec
 *                            d'authentification n'est jamais compté en base
 * @param window              fenêtre fixe de cette limite (1 seconde à 1 heure)
 */
@ConfigurationProperties(prefix = "geststock.integration.maintenance")
public record IntegrationMaintenanceProperties(@DefaultValue("false") boolean enabled, String tokenSha256,
		String previousTokenSha256, @DefaultValue("25s") Duration budget, @DefaultValue("6") int callsPerWindow,
		@DefaultValue("1m") Duration window) {

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

	public IntegrationMaintenanceProperties {
		tokenSha256 = fingerprint(tokenSha256, "token-sha256");
		previousTokenSha256 = fingerprint(previousTokenSha256, "previous-token-sha256");
		budget = budget != null ? budget : Duration.ofSeconds(25);
		window = window != null ? window : Duration.ofMinutes(1);
		if (budget.isNegative() || budget.isZero()) {
			throw new IllegalArgumentException("geststock.integration.maintenance.budget : durée positive attendue.");
		}
		if (callsPerWindow < 1) {
			throw new IllegalArgumentException("geststock.integration.maintenance.calls-per-window : 1 au moins.");
		}
		if (window.compareTo(Duration.ofSeconds(1)) < 0 || window.compareTo(RateLimiter.MAX_WINDOW) > 0) {
			throw new IllegalArgumentException("geststock.integration.maintenance.window : de 1 seconde à 1 heure.");
		}
	}

	/** Vide : absente. Sinon 64 caractères hexadécimaux, mis en minuscules ; la valeur refusée n'est jamais citée. */
	private static String fingerprint(String value, String property) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.strip();
		if (!SHA256_HEX.matcher(trimmed).matches()) {
			throw new IllegalArgumentException("geststock.integration.maintenance." + property
					+ " : empreinte SHA-256 attendue (64 caractères hexadécimaux), jamais le secret lui-même.");
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}

	/** Durée du bail : deux fois le budget (G20-D12) ; un passage normal se termine avant l'échéance. */
	public Duration leaseTtl() {
		return this.budget.multipliedBy(2);
	}

	/** Empreintes acceptées (32 octets chacune) : la courante, puis celle d'une rotation en cours. */
	public List<byte[]> fingerprints() {
		List<byte[]> fingerprints = new ArrayList<>(2);
		for (String value : new String[] { this.tokenSha256, this.previousTokenSha256 }) {
			if (value != null) {
				fingerprints.add(HexFormat.of().parseHex(value));
			}
		}
		return List.copyOf(fingerprints);
	}

	/** Problèmes bloquants en production (liste vide : configuration acceptée). */
	public List<String> problems() {
		if (this.enabled && this.tokenSha256 == null) {
			return List.of("GESTSTOCK_MAINTENANCE_TOKEN_SHA256 est vide : empreinte SHA-256 du secret du cron requise quand "
					+ "la maintenance est activée (geststock.integration.maintenance.enabled).");
		}
		return List.of();
	}

	@Override
	public String toString() {
		return "IntegrationMaintenanceProperties[enabled=" + this.enabled + ", tokenSha256="
				+ (this.tokenSha256 != null ? "[masquée]" : "absente") + ", previousTokenSha256="
				+ (this.previousTokenSha256 != null ? "[masquée]" : "absente") + ", budget=" + this.budget
				+ ", callsPerWindow=" + this.callsPerWindow + ", window=" + this.window + "]";
	}

}
