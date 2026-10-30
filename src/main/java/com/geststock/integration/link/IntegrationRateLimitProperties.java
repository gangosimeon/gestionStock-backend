package com.geststock.integration.link;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limitation de débit des routes d'intégration (lot 2, étape G9 ; v13-D4 complété en v14, v14-D8).
 *
 * <p>{@code client-ip-header} : seul en-tête qui porte l'IP du client, posé par la plateforme d'hébergement (Vercel, à
 * confirmer sur un déploiement de prévisualisation) ; jamais {@code X-Forwarded-For} ni {@code Forwarded}, que le
 * client peut écrire. Non configuré : {@code getRemoteAddr()}, accepté hors production seulement (refusé au démarrage
 * en production quand les liaisons sont activées, {@code ProductionSafetyCheck}). Configuré mais absent de la
 * requête : clé IP unique {@code unknown}. En production : {@code GESTSTOCK_INTEGRATION_CLIENT_IP_HEADER}.
 *
 * @param clientIpHeader en-tête de l'IP du client, ou vide
 * @param rateLimit      limites de départ, configurables
 */
@ConfigurationProperties(prefix = "geststock.integration")
public record IntegrationRateLimitProperties(String clientIpHeader, @DefaultValue RateLimit rateLimit) {

	static final String CLIENT_IP_HEADER_PROPERTY = "geststock.integration.client-ip-header";

	private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");

	private static final Set<String> FORWARDING_HEADERS = Set.of("x-forwarded-for", "forwarded");

	public IntegrationRateLimitProperties {
		clientIpHeader = clientIpHeader == null || clientIpHeader.isBlank() ? null : clientIpHeader.trim();
		List<String> problems = clientIpHeaderProblems(clientIpHeader, false);
		if (!problems.isEmpty()) {
			throw new IllegalArgumentException(String.join(" ; ", problems));
		}
		rateLimit = rateLimit != null ? rateLimit : new RateLimit(null, 10, 10, 30, 30, 5, null);
	}

	/**
	 * Problèmes de l'en-tête d'IP ; vide s'il est acceptable.
	 * @param required en-tête obligatoire (production, liaisons activées)
	 */
	public static List<String> clientIpHeaderProblems(String header, boolean required) {
		List<String> problems = new ArrayList<>();
		if (header == null || header.isBlank()) {
			if (required) {
				problems.add(CLIENT_IP_HEADER_PROPERTY + " est vide : en production, l'IP du client est lue dans un seul "
						+ "en-tête posé par l'hébergeur (GESTSTOCK_INTEGRATION_CLIENT_IP_HEADER, v14-D8).");
			}
			return problems;
		}
		String name = header.trim();
		if (!HEADER_NAME.matcher(name).matches()) {
			problems.add(CLIENT_IP_HEADER_PROPERTY + " : nom d'en-tête « " + name + " » invalide.");
		}
		else if (FORWARDING_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
			problems.add(CLIENT_IP_HEADER_PROPERTY + " : « " + name + " » est écrit par le client lui-même et ne peut pas "
					+ "porter l'IP (v14-D8).");
		}
		return problems;
	}

	/**
	 * @param window              durée de la fenêtre fixe des limites {@code /oauth/*} (1 seconde à 1 heure)
	 * @param oauthTokenPerClient {@code /oauth/token} : requêtes par fenêtre et par client authentifié
	 * @param oauthTokenPerIp     {@code /oauth/token} : requêtes par fenêtre et par IP, tentatives échouées comprises
	 * @param oauthParPerClient   {@code /oauth/par} : requêtes par fenêtre et par client authentifié
	 * @param oauthParPerIp       {@code /oauth/par} : requêtes par fenêtre et par IP, tentatives échouées comprises
	 * @param linkSecretPerLink   {@code /link/secret} (G14) : rotations par fenêtre et par liaison authentifiée
	 * @param linkSecretWindow    fenêtre fixe de {@code /link/secret} (1 seconde à 1 heure ; 1 heure par défaut)
	 */
	public record RateLimit(@DefaultValue("1m") Duration window, @DefaultValue("10") int oauthTokenPerClient,
			@DefaultValue("10") int oauthTokenPerIp, @DefaultValue("30") int oauthParPerClient,
			@DefaultValue("30") int oauthParPerIp, @DefaultValue("5") int linkSecretPerLink,
			@DefaultValue("1h") Duration linkSecretWindow) {

		public RateLimit {
			window = window != null ? window : Duration.ofMinutes(1);
			linkSecretWindow = linkSecretWindow != null ? linkSecretWindow : RateLimiter.MAX_WINDOW;
			if (window.compareTo(Duration.ofSeconds(1)) < 0 || window.compareTo(RateLimiter.MAX_WINDOW) > 0
					|| linkSecretWindow.compareTo(Duration.ofSeconds(1)) < 0
					|| linkSecretWindow.compareTo(RateLimiter.MAX_WINDOW) > 0) {
				throw new IllegalArgumentException(
						"geststock.integration.rate-limit.window et link-secret-window : de 1 seconde à 1 heure.");
			}
			if (oauthTokenPerClient < 1 || oauthTokenPerIp < 1 || oauthParPerClient < 1 || oauthParPerIp < 1
					|| linkSecretPerLink < 1) {
				throw new IllegalArgumentException("geststock.integration.rate-limit : chaque limite vaut au moins 1.");
			}
		}

	}

}
