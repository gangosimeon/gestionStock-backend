package com.geststock.integration.link;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ApiErrorWriter;
import com.geststock.exception.ErrorCode;

/**
 * Limitation de débit des routes du client (lot 2, étape G9 ; v13-D4 complété en v14), en deux temps :
 * <ul>
 * <li>{@link ByIp}, <b>avant</b> l'authentification du client : toutes les tentatives comptent, y compris au mauvais
 * secret ({@code /oauth/token} 10 par minute, {@code /oauth/par} 30 par minute) ;</li>
 * <li>{@link ByClient}, <b>après</b> une authentification Basic réussie seulement ({@code /oauth/token} 10 par minute,
 * {@code /oauth/par} 30 par minute) : annoncer l'identifiant de JeCréeMaBoutique sans son secret n'épuise jamais son
 * quota (sinon, déni de service sur la synchronisation de toutes les boutiques ; contre la force brute, le secret de
 * 256 bits suffit).</li>
 * </ul>
 *
 * <p>Clé IP : en-tête configuré (v14-D8), sinon {@code getRemoteAddr()} (hors production) ; en-tête configuré absent
 * ou hors format : clé unique {@code unknown}. Dépassement : {@code 429 RATE_LIMITED} avec le {@code Retry-After} du
 * compteur qui refuse ; compteurs inaccessibles : {@code 503 RATE_LIMIT_UNAVAILABLE}. Format {@code ApiError} (contrat :
 * réponses {@code TooManyRequests} et {@code RateLimitUnavailable}), {@code Cache-Control: no-store}. Journaux : clé
 * et décision, jamais l'en-tête {@code Authorization}.
 */
abstract sealed class RateLimitFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

	static final String UNKNOWN_IP = "unknown";

	/** Adresse IPv4 ou IPv6 en notation texte (bornée ; jamais une liste). */
	private static final Pattern IP = Pattern.compile("[0-9a-f.:]{2,45}");

	private final RequestMatcher token = PathPatternRequestMatcher.pathPattern(IntegrationSecurityConfig.BASE_PATH + "/oauth/token");

	private final RequestMatcher par = PathPatternRequestMatcher.pathPattern(IntegrationSecurityConfig.BASE_PATH + "/oauth/par");

	final IntegrationRateLimitProperties properties;

	private final RateLimiter limiter;

	private final ApiErrorFactory errors;

	private final ApiErrorWriter writer;

	RateLimitFilter(IntegrationRateLimitProperties properties, RateLimiter limiter, ApiErrorFactory errors,
			ApiErrorWriter writer) {
		this.properties = properties;
		this.limiter = limiter;
		this.errors = errors;
		this.writer = writer;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !this.token.matches(request) && !this.par.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		boolean isToken = this.token.matches(request);
		String subject = subject(request);
		if (subject == null) {
			chain.doFilter(request, response);
			return;
		}
		String key = (isToken ? "oauth-token:" : "oauth-par:") + subject;
		RateLimiter.Decision decision;
		try {
			decision = this.limiter.hit(key, limit(isToken, this.properties.rateLimit()), this.properties.rateLimit().window());
		}
		catch (RateLimiter.Unavailable ex) {
			log.warn("Limitation de débit indisponible ⇒ 503, requête refusée ({} {}) : {}.", request.getMethod(),
					request.getRequestURI(), ex.getCause().getClass().getSimpleName());
			refuse(request, response, ErrorCode.RATE_LIMIT_UNAVAILABLE);
			return;
		}
		if (!decision.allowed()) {
			log.info("Limitation de débit : {} ({} / {}) ⇒ 429, Retry-After {} s.", decision.key(), decision.hits(),
					decision.limit(), decision.retryAfterSeconds());
			response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
			refuse(request, response, ErrorCode.RATE_LIMITED);
			return;
		}
		log.debug("Limitation de débit : {} ({} / {}) ⇒ accepté.", decision.key(), decision.hits(), decision.limit());
		chain.doFilter(request, response);
	}

	/** Sujet du compteur ({@code ip:…} ou {@code client:…}), ou {@code null} : rien à compter à ce stade. */
	abstract String subject(HttpServletRequest request);

	abstract int limit(boolean token, IntegrationRateLimitProperties.RateLimit limits);

	private void refuse(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		response.setHeader(HttpHeaders.PRAGMA, "no-cache");
		this.writer.write(response, this.errors.create(code, null, request));
	}

	/** Avant l'authentification du client : par IP, toutes tentatives comprises. */
	static final class ByIp extends RateLimitFilter {

		ByIp(IntegrationRateLimitProperties properties, RateLimiter limiter, ApiErrorFactory errors, ApiErrorWriter writer) {
			super(properties, limiter, errors, writer);
		}

		/** IP de l'en-tête configuré (jamais {@code X-Forwarded-For}), sinon de la connexion (hors production). */
		@Override
		String subject(HttpServletRequest request) {
			String header = this.properties.clientIpHeader();
			String value = header != null ? request.getHeader(header) : request.getRemoteAddr();
			String ip = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
			boolean valid = IP.matcher(ip).matches() && (ip.indexOf('.') >= 0 || ip.indexOf(':') >= 0);
			return "ip:" + (valid ? ip : UNKNOWN_IP);
		}

		@Override
		int limit(boolean token, IntegrationRateLimitProperties.RateLimit limits) {
			return token ? limits.oauthTokenPerIp() : limits.oauthParPerIp();
		}

	}

	/** Après une authentification Basic réussie : par client authentifié (jamais l'identifiant seulement annoncé). */
	static final class ByClient extends RateLimitFilter {

		ByClient(IntegrationRateLimitProperties properties, RateLimiter limiter, ApiErrorFactory errors,
				ApiErrorWriter writer) {
			super(properties, limiter, errors, writer);
		}

		@Override
		String subject(HttpServletRequest request) {
			return SecurityContextHolder.getContext().getAuthentication() instanceof ClientAuthentication client
					? "client:" + ((IntegrationClients.AuthenticatedClient) client.getPrincipal()).clientId() : null;
		}

		@Override
		int limit(boolean token, IntegrationRateLimitProperties.RateLimit limits) {
			return token ? limits.oauthTokenPerClient() : limits.oauthParPerClient();
		}

	}

}
