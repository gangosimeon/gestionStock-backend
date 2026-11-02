package com.geststock.integration.link;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.config.OpenApiConfig;
import com.geststock.exception.ApiError;
import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.integration.ChangeEvent;
import com.geststock.integration.ChangeFeedService;
import com.geststock.integration.ChangePage;
import com.geststock.integration.IntegrationOperation;
import com.geststock.integration.LinkRef;

import tools.jackson.databind.JsonNode;

/**
 * Flux des changements de l'entreprise liée (lot 3, étape G16 ; contrat {@code GET /catalog/changes}, portée
 * {@code catalog:read}). Lu par {@link ChangeFeedService} dans la base de l'entreprise de la liaison (entreprise
 * courante posée par la chaîne d'intégration), base primaire, sans cache (P0-5) ; catalogue actif entier (Q2), la
 * disponibilité seulement avec {@code stock:read} ({@link LinkChangeFilters}). Limite : 120 appels par minute et par liaison (v13-D4, configurable), comptés avant la lecture
 * des paramètres.
 *
 * <p>Les paramètres sont lus ici plutôt que liés par Spring MVC : la conversion de Spring cite la valeur refusée dans ses
 * journaux, et le pare-feu de Spring Security la cite dans son exception (G15 : aucune valeur reçue dans les journaux).
 * {@code after} absent, vide, non entier ou répété, de même pour {@code limit} : {@code 400 INVALID_PARAMETER} ; hors
 * bornes : {@code 400 VALIDATION_FAILED}, paramètre fautif dans {@code errors} (v11-D3).
 */
@RestController
@RequestMapping(IntegrationSecurityConfig.BASE_PATH + "/catalog")
@Tag(name = "Intégration JeCréeMaBoutique", description = "Routes du client d'intégration (contrat integration-v1)")
class CatalogChangesController {

	private static final Logger log = LoggerFactory.getLogger(CatalogChangesController.class);

	private static final String API_ERROR = "#/components/schemas/ApiError";

	/** Entier décimal signé ; un dépassement de {@code long} est refusé à la conversion. */
	private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]{1,19}");

	private static final int MAX_LIMIT = 500;

	private final ChangeFeedService feed;

	private final RateLimiter limiter;

	private final IntegrationRateLimitProperties rateLimits;

	private final ApiErrorFactory errors;

	CatalogChangesController(ChangeFeedService feed, RateLimiter limiter, IntegrationRateLimitProperties rateLimits,
			ApiErrorFactory errors) {
		this.feed = feed;
		this.limiter = limiter;
		this.rateLimits = rateLimits;
		this.errors = errors;
	}

	@GetMapping("/changes")
	@RequiresScope("catalog:read")
	@Operation(operationId = "listCatalogChanges", summary = "Flux ordonné des changements de catalogue, prix et disponibilité",
			description = "Page des événements de seq > after, par seq croissant (C3, P0 à P5). lastSeq : dernier seq examiné, "
					+ "à enregistrer comme nouvel after ; hasMore : rappeler immédiatement. Un type inconnu est servi tel "
					+ "qu'il est stocké. Catalogue actif entier ; événements AVAILABILITY servis seulement avec la portée "
					+ "stock:read (écartés sinon, lastSeq avance quand même). Limite : 120 appels par minute "
					+ "et par liaison (configurable), 429 RATE_LIMITED avec Retry-After.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			parameters = {
					@Parameter(name = "after", in = ParameterIn.QUERY, required = true,
							description = "Dernier seq consommé (0 au départ)",
							schema = @Schema(type = "integer", format = "int64", minimum = "0")),
					@Parameter(name = "limit", in = ParameterIn.QUERY,
							description = "Nombre maximal d'événements examinés (100 par défaut)",
							schema = @Schema(type = "integer", minimum = "1", maximum = "500", defaultValue = "100")) },
			responses = { @ApiResponse(responseCode = "200", description = "Page d'événements"),
					@ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (absent, non entier, répété), "
							+ "VALIDATION_FAILED (hors bornes)",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "409", description = "CURSOR_AHEAD : after dépasse le dernier seq attribué",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "410", description = "CURSOR_EXPIRED : after est sous le repère de purge",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED : plus de 120 appels par minute pour la "
							+ "liaison (valeur de départ, configurable) ; en-tête Retry-After",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	ChangePageView changes(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			HttpServletRequest request) {
		IntegrationRateLimitProperties.RateLimit limits = this.rateLimits.rateLimit();
		RateLimiter.Decision decision = this.limiter.hit("catalog-changes:link:" + principal.linkId(),
				limits.catalogChangesPerLink(), limits.window());
		if (!decision.allowed()) {
			log.info("Limitation de débit : {} ({} / {}) ⇒ 429, Retry-After {} s.", decision.key(), decision.hits(),
					decision.limit(), decision.retryAfterSeconds());
			throw new ChangesRateLimited(decision.retryAfterSeconds());
		}
		Map<String, String[]> parameters;
		try {
			parameters = request.getParameterMap();
		}
		catch (RequestRejectedException ex) {
			// Pare-feu de Spring Security : nom ou valeur de paramètre refusé à la lecture. Son message porte la valeur
			// reçue : ni journalisé ni repris.
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre refusé.");
		}
		Long after = integer(parameters, "after");
		if (after == null) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre requis : after.");
		}
		Long limit = integer(parameters, "limit");
		if (after < 0) {
			throw new OutOfBounds("after", "doit être supérieur ou égal à 0");
		}
		if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
			throw new OutOfBounds("limit", limit < 1 ? "doit être supérieur ou égal à 1"
					: "doit être inférieur ou égal à " + MAX_LIMIT);
		}
		ChangePage page = this.feed.read(new LinkRef(principal.linkId()), after,
				limit != null ? Math.toIntExact(limit) : null, LinkChangeFilters.of(principal));
		return ChangePageView.of(page);
	}

	/** Valeur entière du paramètre, {@code null} s'il est absent ; refusée si elle est vide, non entière ou répétée. */
	private static Long integer(Map<String, String[]> parameters, String name) {
		String[] values = parameters.get(name);
		if (values == null) {
			return null;
		}
		if (values.length != 1) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre répété : " + name + ".");
		}
		if (INTEGER.matcher(values[0]).matches()) {
			try {
				return Long.parseLong(values[0]);
			}
			catch (NumberFormatException ex) {
				// Dépassement de long : même réponse qu'une valeur non entière.
			}
		}
		throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre invalide : " + name + ".");
	}

	@ExceptionHandler(OutOfBounds.class)
	ResponseEntity<ApiError> outOfBounds(OutOfBounds ex, HttpServletRequest request) {
		ApiError.FieldViolation violation = new ApiError.FieldViolation(ex.field, ex.constraint);
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
					"Données invalides : " + ex.field + " " + ex.constraint, List.of(violation), Map.of(),
					ApiErrorFactory.path(request)));
	}

	@ExceptionHandler(ChangesRateLimited.class)
	ResponseEntity<ApiError> rateLimited(ChangesRateLimited ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds))
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.PRAGMA, "no-cache")
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.RATE_LIMITED, null, request));
	}

	/** Compteurs de débit inaccessibles : jamais de laisser-passer (v13-D4). */
	@ExceptionHandler(RateLimiter.Unavailable.class)
	ResponseEntity<ApiError> rateLimitUnavailable(RateLimiter.Unavailable ex, HttpServletRequest request) {
		log.warn("Limitation de débit indisponible ⇒ 503, lecture du flux refusée : {}.",
				ex.getCause().getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
			.header(HttpHeaders.PRAGMA, "no-cache")
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.RATE_LIMIT_UNAVAILABLE, null, request));
	}

	/**
	 * Réponse {@code 200} (contrat {@code ChangePage}).
	 *
	 * @param items   événements servis, par {@code seq} croissant
	 * @param hasMore P2 : rappeler immédiatement avec {@code after = lastSeq}
	 * @param lastSeq P1 : dernier {@code seq} examiné, à enregistrer comme nouvel {@code after}
	 */
	@Schema(name = "IntegrationChangePage")
	record ChangePageView(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ChangeEventView> items,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long lastSeq) {

		static ChangePageView of(ChangePage page) {
			return new ChangePageView(page.items().stream().map(ChangeEventView::of).toList(), page.hasMore(), page.lastSeq());
		}

	}

	/**
	 * Événement servi (contrat {@code ChangeEventBase}) : {@code type} tel qu'il est stocké, même inconnu de cette version
	 * (v11-D4) ; {@code data} absent quand l'événement n'en porte pas ({@code ARCHIVE}).
	 */
	@Schema(name = "IntegrationChangeEvent")
	record ChangeEventView(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long seq,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID objectId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) IntegrationOperation operation,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
			@JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "État de l'objet ; obligatoire pour UPSERT")
			JsonNode data) {

		static ChangeEventView of(ChangeEvent event) {
			return new ChangeEventView(event.seq(), event.typeName(), event.objectId(), event.version(), event.operation(),
					event.occurredAt(), event.data());
		}

	}

	/** Paramètre hors bornes : {@code VALIDATION_FAILED}, paramètre fautif dans {@code errors}. */
	static final class OutOfBounds extends RuntimeException {

		private final String field;

		private final String constraint;

		OutOfBounds(String field, String constraint) {
			super("Paramètre hors bornes : " + field, null, false, false);
			this.field = field;
			this.constraint = constraint;
		}

	}

	/** Quota de lecture du flux de la liaison épuisé. */
	static final class ChangesRateLimited extends RuntimeException {

		private final long retryAfterSeconds;

		ChangesRateLimited(long retryAfterSeconds) {
			super("Lectures du flux trop fréquentes", null, false, false);
			this.retryAfterSeconds = retryAfterSeconds;
		}

	}

}
