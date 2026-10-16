package com.geststock.exception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Traduction de toutes les exceptions levées dans Spring MVC en {@link ApiError}.
 *
 * <p>Règles :
 * <ul>
 * <li>le corps contient toujours {@code message} (lu par le front) ;</li>
 * <li>aucun détail technique (SQL, stack trace) n'est renvoyé ;</li>
 * <li>les erreurs 5xx sont journalisées avec leur {@code traceId}.</li>
 * </ul>
 * Les exceptions de sécurité ({@link AccessDeniedException}, {@link AuthenticationException})
 * sont volontairement relancées : Spring Security les traite (401 pour un anonyme,
 * 403 sinon) avec le même format, via {@code RestAuthenticationEntryPoint} et
 * {@code RestAccessDeniedHandler}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final ApiErrorFactory errorFactory;

	private final Map<String, ErrorCode> constraintCodes;

	public GlobalExceptionHandler(ApiErrorFactory errorFactory, ObjectProvider<ConstraintErrorMapping> constraintMappings) {
		this.errorFactory = errorFactory;
		Map<String, ErrorCode> codes = new HashMap<>();
		constraintMappings.orderedStream()
			.forEach(mapping -> mapping.mappings()
				.forEach((name, code) -> codes.put(name.toLowerCase(Locale.ROOT), code)));
		this.constraintCodes = Map.copyOf(codes);
	}

	// --- Exceptions applicatives ---------------------------------------------------------

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
		return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), List.of(), ex.getDetails(), request);
	}

	// --- Validation et lecture de la requête ---------------------------------------------

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		List<ApiError.FieldViolation> violations = new ArrayList<>();
		ex.getBindingResult().getFieldErrors()
			.forEach(error -> violations.add(new ApiError.FieldViolation(error.getField(), error.getDefaultMessage())));
		ex.getBindingResult().getGlobalErrors()
			.forEach(error -> violations.add(new ApiError.FieldViolation(error.getObjectName(), error.getDefaultMessage())));
		return validationError(violations, request);
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
		List<ApiError.FieldViolation> violations = new ArrayList<>();
		ex.getParameterValidationResults().forEach(result -> {
			String parameter = Optional.ofNullable(result.getMethodParameter().getParameterName()).orElse("parameter");
			result.getResolvableErrors().forEach(error -> {
				String field = (error instanceof FieldError fieldError) ? parameter + "." + fieldError.getField() : parameter;
				violations.add(new ApiError.FieldViolation(field, error.getDefaultMessage()));
			});
		});
		return validationError(violations, request);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
		List<ApiError.FieldViolation> violations = ex.getConstraintViolations().stream()
			.map(violation -> new ApiError.FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
			.toList();
		return validationError(violations, request);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return respond(ErrorCode.MALFORMED_REQUEST, null, request);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
		return respond(ErrorCode.INVALID_PARAMETER, "Paramètre invalide : " + ex.getName() + ".", request);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
		return respond(ErrorCode.INVALID_PARAMETER, "Paramètre requis : " + ex.getParameterName() + ".", request);
	}

	@ExceptionHandler(MissingRequestHeaderException.class)
	ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
		return respond(ErrorCode.INVALID_PARAMETER, "En-tête requis : " + ex.getHeaderName() + ".", request);
	}

	// --- Envoi de fichiers -----------------------------------------------------------------

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
		return respond(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.defaultMessage(), request);
	}

	@ExceptionHandler(MissingServletRequestPartException.class)
	ResponseEntity<ApiError> handleMissingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
		return respond(ErrorCode.INVALID_PARAMETER, "Fichier requis : " + ex.getRequestPartName() + ".", request);
	}

	@ExceptionHandler(MultipartException.class)
	ResponseEntity<ApiError> handleMultipart(MultipartException ex, HttpServletRequest request) {
		log.debug("Envoi de fichier illisible : {}", ex.getMessage());
		return respond(ErrorCode.MALFORMED_REQUEST, "Envoi de fichier illisible : réessayez.", request);
	}

	// --- Routage HTTP ----------------------------------------------------------------------

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
		return respond(ErrorCode.RESOURCE_NOT_FOUND, null, request);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
			HttpServletRequest request) {
		ApiError body = this.errorFactory.create(ErrorCode.METHOD_NOT_ALLOWED, null, request);
		HttpHeaders headers = new HttpHeaders();
		if (ex.getSupportedHttpMethods() != null) {
			headers.setAllow(ex.getSupportedHttpMethods());
		}
		return ResponseEntity.status(body.status()).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ApiError> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
			HttpServletRequest request) {
		return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, request);
	}

	// --- Persistance -----------------------------------------------------------------------

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
		return respond(ErrorCode.CONCURRENT_MODIFICATION, null, request);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
		ErrorCode code = constraintName(ex)
			.map(name -> this.constraintCodes.get(name.toLowerCase(Locale.ROOT)))
			.orElse(ErrorCode.DATA_INTEGRITY_VIOLATION);
		log.warn("Violation d'intégrité des données [{}] : {}", code, ex.getMostSpecificCause().getMessage());
		return respond(code, null, request);
	}

	// --- Sécurité : délégation à Spring Security -------------------------------------------

	@ExceptionHandler({ AccessDeniedException.class, AuthenticationException.class })
	void rethrowSecurityException(RuntimeException ex) {
		throw ex;
	}

	// --- Autres erreurs du framework et filet de sécurité ----------------------------------

	/**
	 * Filet de sécurité. Les exceptions du framework qui portent leur propre statut
	 * ({@link ErrorResponse}, ex. {@code ResponseStatusException}) le conservent ; tout le
	 * reste devient une 500 générique, journalisée.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
		HttpStatusCode status = (ex instanceof ErrorResponse errorResponse) ? errorResponse.getStatusCode()
				: ErrorCode.INTERNAL_ERROR.status();
		if (status.is5xxServerError()) {
			log.error("Erreur inattendue sur {} {}", request.getMethod(), request.getRequestURI(), ex);
		}
		return respond(status, ErrorCode.fromStatus(status), null, List.of(), Map.of(), request);
	}

	// --- Outils ----------------------------------------------------------------------------

	private ResponseEntity<ApiError> validationError(List<ApiError.FieldViolation> violations, HttpServletRequest request) {
		String message = violations.isEmpty() ? null
				: "Données invalides : " + violations.get(0).field() + " " + violations.get(0).message()
						+ (violations.size() > 1 ? " (et " + (violations.size() - 1) + " autre(s) erreur(s))" : "");
		return respond(ErrorCode.VALIDATION_FAILED.status(), ErrorCode.VALIDATION_FAILED, message, violations, Map.of(),
				request);
	}

	private ResponseEntity<ApiError> respond(ErrorCode code, String message, HttpServletRequest request) {
		return respond(code.status(), code, message, List.of(), Map.of(), request);
	}

	private ResponseEntity<ApiError> respond(HttpStatusCode status, ErrorCode code, String message,
			List<ApiError.FieldViolation> violations, Map<String, Object> details, HttpServletRequest request) {
		ApiError body = this.errorFactory.create(status, code, message, violations, details, ApiErrorFactory.path(request));
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}

	private static Optional<String> constraintName(DataIntegrityViolationException ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof org.hibernate.exception.ConstraintViolationException hibernateViolation
					&& StringUtils.hasText(hibernateViolation.getConstraintName())) {
				return Optional.of(hibernateViolation.getConstraintName());
			}
			cause = cause.getCause();
		}
		return Optional.empty();
	}

}
