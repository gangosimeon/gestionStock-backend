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

import com.geststock.common.web.TraceIdFilter;

/**
 * Construit les {@link ApiError}. Point unique utilisé par le gestionnaire d'exceptions MVC,
 * le contrôleur {@code /error} et les gestionnaires de Spring Security, afin que toutes les
 * erreurs aient exactement la même forme.
 */
@Component
public class ApiErrorFactory {

	private static final String TYPE_PREFIX = "urn:geststock:error:";

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
