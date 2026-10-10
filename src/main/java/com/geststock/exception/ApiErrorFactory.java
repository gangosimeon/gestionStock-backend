package com.geststock.exception;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.geststock.common.web.TraceIdFilter;

/**
 * Construit les {@link ApiError}. Point unique utilisé par le gestionnaire d'exceptions MVC,
 * le contrôleur {@code /error} et les gestionnaires de Spring Security, afin que toutes les
 * erreurs aient exactement la même forme.
 */
@Component
public class ApiErrorFactory {

	private static final String TYPE_PREFIX = "urn:geststock:error:";

	/** Attribut de requête portant le code de l'erreur renvoyée (lu par le journal d'audit). */
	public static final String ERROR_CODE_ATTRIBUTE = ApiErrorFactory.class.getName() + ".code";

	/** Attribut de requête portant le message de l'erreur renvoyée. */
	public static final String ERROR_MESSAGE_ATTRIBUTE = ApiErrorFactory.class.getName() + ".message";

	private final Clock clock;

	public ApiErrorFactory(Clock clock) {
		this.clock = clock;
	}

	public ApiError create(ErrorCode code, String message, HttpServletRequest request) {
		return create(code.status(), code, message, List.of(), Map.of(), path(request));
	}

	public ApiError create(HttpStatusCode status, ErrorCode code, String message, List<ApiError.FieldViolation> errors,
			Map<String, Object> details, String path) {
		String effectiveMessage = StringUtils.hasText(message) ? message : code.defaultMessage();
		HttpStatus resolved = HttpStatus.resolve(status.value());
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes != null) {
			attributes.setAttribute(ERROR_CODE_ATTRIBUTE, code.name(), RequestAttributes.SCOPE_REQUEST);
			attributes.setAttribute(ERROR_MESSAGE_ATTRIBUTE, effectiveMessage, RequestAttributes.SCOPE_REQUEST);
		}
		return new ApiError(
				TYPE_PREFIX + code.name().toLowerCase(Locale.ROOT).replace('_', '-'),
				resolved != null ? resolved.getReasonPhrase() : String.valueOf(status.value()),
				status.value(),
				code.name(),
				effectiveMessage,
				effectiveMessage,
				errors != null ? List.copyOf(errors) : List.of(),
				details != null ? Map.copyOf(details) : Map.of(),
				MDC.get(TraceIdFilter.MDC_KEY),
				Instant.now(this.clock),
				path);
	}

	public static String path(HttpServletRequest request) {
		return request != null ? request.getRequestURI() : null;
	}

}
