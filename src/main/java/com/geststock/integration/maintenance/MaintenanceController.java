package com.geststock.integration.maintenance;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.exception.ApiError;
import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ErrorCode;
import com.geststock.integration.link.RateLimiter;

/**
 * {@code POST /internal/integration/maintenance} (lot 3, G20 ; plan § 33) : route interne du cron, hors du contrat
 * d'intégration et de la documentation OpenAPI ({@code /api/**} seulement). Secret vérifié par
 * {@link MaintenanceSecurityConfig} ; ici, dans l'ordre : limite des appels authentifiés ({@code 429}, compteur
 * {@value #CALLS_COUNTER} en base), bail ({@code 409}), passage.
 */
@RestController
class MaintenanceController {

	private static final Logger log = LoggerFactory.getLogger(MaintenanceController.class);

	/** Compteur des appels authentifiés, tous cron confondus (G20-D14). */
	static final String CALLS_COUNTER = "maintenance:calls";

	private final MaintenanceService maintenance;

	private final RateLimiter limiter;

	private final IntegrationMaintenanceProperties properties;

	private final ApiErrorFactory errors;

	MaintenanceController(MaintenanceService maintenance, RateLimiter limiter, IntegrationMaintenanceProperties properties,
			ApiErrorFactory errors) {
		this.maintenance = maintenance;
		this.limiter = limiter;
		this.properties = properties;
		this.errors = errors;
	}

	@PostMapping(MaintenanceSecurityConfig.ROUTE)
	ResponseEntity<MaintenanceReport> run() {
		RateLimiter.Decision decision = this.limiter.hit(CALLS_COUNTER, this.properties.callsPerWindow(),
				this.properties.window());
		if (!decision.allowed()) {
			log.info("Maintenance : {} appels authentifiés dans la fenêtre ⇒ 429, Retry-After {} s.", decision.hits(),
					decision.retryAfterSeconds());
			throw new CallsLimited(decision.retryAfterSeconds());
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.maintenance.run());
	}

	@ExceptionHandler(MaintenanceService.MaintenanceInProgressException.class)
	ResponseEntity<ApiError> inProgress(HttpServletRequest request) {
		log.info("Maintenance : passage déjà en cours ⇒ 409.");
		return problem(ResponseEntity.status(HttpStatus.CONFLICT), ErrorCode.MAINTENANCE_IN_PROGRESS, request);
	}

	@ExceptionHandler(CallsLimited.class)
	ResponseEntity<ApiError> limited(CallsLimited ex, HttpServletRequest request) {
		return problem(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds)), ErrorCode.RATE_LIMITED, request);
	}

	@ExceptionHandler(RateLimiter.Unavailable.class)
	ResponseEntity<ApiError> limiterUnavailable(RateLimiter.Unavailable ex, HttpServletRequest request) {
		log.warn("Maintenance : limitation de débit indisponible ⇒ 503, passage refusé : {}.",
				ex.getCause().getClass().getSimpleName());
		return problem(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE), ErrorCode.RATE_LIMIT_UNAVAILABLE, request);
	}

	/** Réponse d'erreur au format {@code ApiError}, sans cache. */
	private ResponseEntity<ApiError> problem(ResponseEntity.BodyBuilder response, ErrorCode code, HttpServletRequest request) {
		return response.cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(code, null, request));
	}

	/** Plus d'appels authentifiés que la limite dans la fenêtre. */
	static final class CallsLimited extends RuntimeException {

		private final long retryAfterSeconds;

		CallsLimited(long retryAfterSeconds) {
			super("Appels de maintenance trop fréquents", null, false, false);
			this.retryAfterSeconds = retryAfterSeconds;
		}

	}

}
