package com.geststock.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Attribue un identifiant de corrélation à chaque requête : repris dans les logs
 * ({@code %X{traceId}}), dans le corps des erreurs et dans l'en-tête de réponse
 * {@value #HEADER}. Un identifiant fourni par le client (ou un proxy) est conservé s'il est
 * bien formé.
 *
 * <p>Exécuté avant la chaîne Spring Security pour que les erreurs 401/403 portent aussi
 * le {@code traceId}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Trace-Id";

	public static final String MDC_KEY = "traceId";

	private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		MDC.put(MDC_KEY, resolveTraceId(request, response));
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_KEY);
		}
	}

	/**
	 * Le dispatch {@code /error} survient après la fin de la requête d'origine (MDC déjà
	 * nettoyé) : on le filtre aussi, en reprenant l'identifiant déjà posé dans la réponse.
	 */
	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		doFilterInternal(request, response, chain);
	}

	private static String resolveTraceId(HttpServletRequest request, HttpServletResponse response) {
		String existing = response.getHeader(HEADER);
		if (existing != null) {
			return existing;
		}
		String incoming = request.getHeader(HEADER);
		String traceId = (incoming != null && VALID_TRACE_ID.matcher(incoming).matches()) ? incoming
				: UUID.randomUUID().toString().replace("-", "");
		response.setHeader(HEADER, traceId);
		return traceId;
	}

}
