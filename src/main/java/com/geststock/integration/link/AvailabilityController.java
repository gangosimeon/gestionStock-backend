package com.geststock.integration.link;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.config.OpenApiConfig;
import com.geststock.exception.ApiError;
import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.integration.AvailabilityProvider;
import com.geststock.integration.LinkRef;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Disponibilité indicative par la boutique liée (lot 3, G27 ; {@code GET /availability}, portée
 * {@code stock:read}). Calcul immédiat au moment de la requête, non garanti (seule une réservation garantit
 * une quantité). Produits demandés absents du périmètre de la liaison omis. Limite : 120 appels par minute
 * et par liaison (configurable).
 */
@RestController
@RequestMapping(IntegrationSecurityConfig.BASE_PATH + "/availability")
@Tag(name = "Intégration JeCréeMaBoutique", description = "Routes du client d'intégration (contrat integration-v1)")
class AvailabilityController {

	private static final Logger log = LoggerFactory.getLogger(AvailabilityController.class);

	private static final String API_ERROR = "#/components/schemas/ApiError";

	/** Identifiant sous forme canonique 8-4-4-4-12 (G19-D5). */
	private static final Pattern CANONICAL_UUID = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private static final int MAX_PRODUCTS = 100;

	private static final String KNOWN_PRODUCTS = "select id from product where id in (:ids)";

	private final AvailabilityProvider availability;

	private final JdbcClient jdbc;

	private final RateLimiter limiter;

	private final IntegrationRateLimitProperties rateLimits;

	private final ApiErrorFactory errors;

	private final Clock clock;

	AvailabilityController(AvailabilityProvider availability, DataSource dataSource, RateLimiter limiter,
			IntegrationRateLimitProperties rateLimits, ApiErrorFactory errors, Clock clock) {
		this.availability = availability;
		this.jdbc = JdbcClient.create(dataSource);
		this.limiter = limiter;
		this.rateLimits = rateLimits;
		this.errors = errors;
		this.clock = clock;
	}

	@GetMapping
	@RequiresScope("stock:read")
	@Operation(operationId = "getAvailability", summary = "Disponibilité indicative sur les seuls dépôts associés actifs (G27)",
			description = "Disponibilité de la liaison du jeton pour chacun des produits demandés. "
					+ "`available` = stock − réservations actives des dépôts associés actifs ; "
					+ "produit absent du périmètre omis. Non garanti : seule une réservation garantit une quantité. "
					+ "Limite : 120 appels par minute et par liaison (configurable).",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			parameters = @Parameter(name = "productIds", in = ParameterIn.QUERY, required = true,
					description = "1 à 100 identifiants de produits, forme canonique, séparés par des virgules",
					schema = @Schema(type = "string")),
			responses = { @ApiResponse(responseCode = "200", description = "Disponibilités",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AvailabilityResponseView.class))),
					@ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (absent, vide, répété, non "
							+ "canonique), VALIDATION_FAILED (plus de 100 identifiants)",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
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
	AvailabilityResponseView getAvailability(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			HttpServletRequest request) {
		limit("availability:link:" + principal.linkId(), this.rateLimits.rateLimit().availabilityPerLink());
		Map<String, String[]> parameters = CatalogChangesController.parameters(request);
		String value = CatalogChangesController.text(parameters, "productIds");
		if (value == null || value.isEmpty()) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre requis : productIds.");
		}
		String[] parts = value.split(",", -1);
		if (parts.length > MAX_PRODUCTS) {
			throw new CatalogChangesController.OutOfBounds("productIds",
					"doit contenir au plus " + MAX_PRODUCTS + " identifiants");
		}
		List<UUID> ids = new ArrayList<>(parts.length);
		for (String part : parts) {
			if (!CANONICAL_UUID.matcher(part).matches()) {
				throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre invalide : productIds.");
			}
			ids.add(UUID.fromString(part));
		}
		Set<UUID> knownIds = new HashSet<>(this.jdbc.sql(KNOWN_PRODUCTS).param("ids", new HashSet<>(ids))
			.query(UUID.class)
			.list());
		Instant computedAt = Instant.now(this.clock);
		Map<UUID, ?> values = this.availability.availability(new LinkRef(principal.linkId()), knownIds);
		List<Object> items = new ArrayList<>(knownIds.size());
		for (UUID id : ids) {
			Object av = values.get(id);
			if (av != null) {
				items.add(av);
			}
		}
		return new AvailabilityResponseView(computedAt, items);
	}

	/** Compte l'appel sous {@code key} ; au-delà de {@code limit} dans la fenêtre : {@code 429} avec {@code Retry-After}. */
	private void limit(String key, int limit) {
		RateLimiter.Decision decision = this.limiter.hit(key, limit, this.rateLimits.rateLimit().window());
		if (!decision.allowed()) {
			log.info("Limitation de débit (disponibilité) : {} ({} / {}) ⇒ 429, Retry-After {} s.",
					decision.key(), decision.hits(), decision.limit(), decision.retryAfterSeconds());
			throw new CatalogChangesController.ChangesRateLimited(decision.retryAfterSeconds());
		}
	}

	@ExceptionHandler(CatalogChangesController.OutOfBounds.class)
	ResponseEntity<ApiError> outOfBounds(CatalogChangesController.OutOfBounds ex, HttpServletRequest request) {
		ApiError.FieldViolation violation = new ApiError.FieldViolation(ex.field(), ex.constraint());
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
					"Données invalides : " + ex.field() + " " + ex.constraint(), List.of(violation), Map.of(),
					ApiErrorFactory.path(request)));
	}

	@ExceptionHandler(CatalogChangesController.ChangesRateLimited.class)
	ResponseEntity<ApiError> rateLimited(CatalogChangesController.ChangesRateLimited ex, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.RATE_LIMITED, null, request));
	}

	@ExceptionHandler(RateLimiter.Unavailable.class)
	ResponseEntity<ApiError> rateLimitUnavailable(RateLimiter.Unavailable ex, HttpServletRequest request) {
		log.warn("Limitation de débit indisponible ⇒ 503, disponibilité refusée : {}.",
				ex.getCause().getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.RATE_LIMIT_UNAVAILABLE, null, request));
	}

	/** Réponse de {@code GET /availability} (G27). */
	@Schema(name = "IntegrationAvailabilityResponse")
	record AvailabilityResponseView(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time") Instant computedAt,
			@ArraySchema(schema = @Schema(implementation = AvailabilityItemView.class)) List<Object> items) {
	}

	/**
	 * Schéma {@code Availability} du contrat : disponibilité d'un produit par la liaison.
	 *
	 * @param productId    produit
	 * @param available    stock disponible (jamais négatif)
	 * @param perWarehouse détail par dépôt du périmètre
	 * @param computedAt   moment du calcul
	 */
	@Schema(name = "Availability")
	record AvailabilityItemView(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID productId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int available,
			List<WarehouseAvailabilityView> perWarehouse,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string",
					format = "date-time") Instant computedAt) {
	}

	/** Élément de {@code Availability.perWarehouse}. */
	record WarehouseAvailabilityView(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID warehouseId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") int available) {
	}

}
