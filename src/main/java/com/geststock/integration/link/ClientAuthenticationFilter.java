package com.geststock.integration.link;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentification HTTP Basic du client JeCréeMaBoutique sur {@code /oauth/par} et {@code /oauth/token} (lot 2, étape
 * G8 ; v14-D3 ; RFC 6749 § 2.3.1 : identifiant et secret encodés en {@code application/x-www-form-urlencoded} avant
 * Base64). Échec ⇒ {@code 401 invalid_client} au format OAuth, avec {@code WWW-Authenticate: Basic} ; même réponse
 * pour un client inconnu, désactivé, un secret faux ou un en-tête absent ou illisible.
 */
final class ClientAuthenticationFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ClientAuthenticationFilter.class);

	/** Format des identifiants de client ({@code ck_integration_client_client_id}) ; hors format : {@code ?}. */
	private static final java.util.regex.Pattern ANNOUNCED_CLIENT = java.util.regex.Pattern
		.compile("[a-z0-9][a-z0-9-]{2,62}[a-z0-9]");

	static final String CHALLENGE = "Basic realm=\"geststock-integration\", charset=\"UTF-8\"";

	private static final String BODY = "{\"error\":\"invalid_client\",\"error_description\":\"Authentification du client refusée.\"}";

	private final RequestMatcher clientPaths;

	private final IntegrationClients clients;

	private final LinkAudit audit;

	private final String clientIpHeader;

	ClientAuthenticationFilter(RequestMatcher clientPaths, IntegrationClients clients, LinkAudit audit,
			String clientIpHeader) {
		this.clientPaths = clientPaths;
		this.clients = clients;
		this.audit = audit;
		this.clientIpHeader = clientIpHeader;
	}

	/**
	 * Trace d'audit de l'échec (G12) : identifiant annoncé (normalisé, jamais le secret) et IP. Base plateforme
	 * indisponible : WARN, la réponse reste 401.
	 */
	private void trace(HttpServletRequest request, String announced) {
		String normalized = announced == null ? null : announced.trim().toLowerCase(java.util.Locale.ROOT);
		if (normalized != null && !ANNOUNCED_CLIENT.matcher(normalized).matches()) {
			normalized = "?";
		}
		try {
			this.audit.recordAlone(new LinkAudit.Entry(LinkAudit.CLIENT_AUTHENTICATION_FAILED, LinkAudit.Severity.WARNING, null,
					null, null, normalized, null, null, new LinkAudit.Actor(LinkAudit.ActorType.CLIENT, null, normalized),
					RateLimitFilter.clientIp(request, this.clientIpHeader), null, new java.util.LinkedHashMap<>()));
		}
		catch (RuntimeException ex) {
			log.warn("Échec d'authentification du client non tracé ({}).", ex.getClass().getSimpleName());
		}
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !this.clientPaths.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Optional<String[]> credentials = credentials(request.getHeader(HttpHeaders.AUTHORIZATION));
		Optional<IntegrationClients.AuthenticatedClient> client = credentials
			.flatMap(pair -> this.clients.authenticate(pair[0], pair[1]));
		if (client.isEmpty()) {
			log.info("Authentification du client refusée ({} {}).", request.getMethod(), request.getRequestURI());
			trace(request, credentials.map(pair -> pair[0]).orElse(null));
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
			response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
			response.setHeader(HttpHeaders.PRAGMA, "no-cache");
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write(BODY);
			return;
		}
		SecurityContextHolder.getContext().setAuthentication(new ClientAuthentication(client.get()));
		chain.doFilter(request, response);
	}

	/** Identifiant et secret du client, ou rien si l'en-tête est absent ou illisible. */
	private static Optional<String[]> credentials(String header) {
		if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
			return Optional.empty();
		}
		try {
			String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
			int colon = decoded.indexOf(':');
			if (colon <= 0) {
				return Optional.empty();
			}
			return Optional.of(new String[] { URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
					URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8) });
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
