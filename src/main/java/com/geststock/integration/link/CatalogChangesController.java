package com.geststock.integration.link;

import java.io.IOException;
import java.io.InputStream;
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
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
import com.geststock.integration.SnapshotPage;
import com.geststock.integration.SnapshotService;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

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
 *
 * <p>Acquittement ({@code POST /catalog/changes/ack}, lot 3, étape G17 ; plan § 30.3) : dans l'ordre, chaîne de
 * sécurité, limite de débit (60 par minute et par liaison, v17-D7, comptée avant la lecture du corps, v17-D14), type de
 * contenu, lecture stricte du corps (v17-D12), enregistrement ({@link LinkAcknowledgements} : jamais en recul,
 * {@code 409 CURSOR_AHEAD} au-delà du flux). Le corps est lu ici, et non par un {@code @RequestBody} que Spring MVC lirait
 * avant la méthode ; la route ne déclare pas {@code consumes} : la chaîne d'intégration ne trouverait alors aucune
 * règle pour un autre type de contenu et répondrait {@code 403} au lieu de {@code 415}.
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

	/** Taille maximale du corps d'un acquittement ({@code {"seq": <entier>}}). */
	static final int MAX_ACK_BODY = 1024;

	private final ChangeFeedService feed;

	private final RateLimiter limiter;

	private final IntegrationRateLimitProperties rateLimits;

	private final ApiErrorFactory errors;

	private final LinkAcknowledgements acknowledgements;

	private final SnapshotService snapshots;

	/** Lecture stricte du corps : clés répétées et contenu après l'objet refusés (v17-D12). */
	private final ObjectReader strictJson;

	CatalogChangesController(ChangeFeedService feed, RateLimiter limiter, IntegrationRateLimitProperties rateLimits,
			ApiErrorFactory errors, LinkAcknowledgements acknowledgements, SnapshotService snapshots, JsonMapper json) {
		this.feed = feed;
		this.limiter = limiter;
		this.rateLimits = rateLimits;
		this.errors = errors;
		this.acknowledgements = acknowledgements;
		this.snapshots = snapshots;
		this.strictJson = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
		limit("catalog-changes:link:" + principal.linkId(), this.rateLimits.rateLimit().catalogChangesPerLink());
		Map<String, String[]> parameters = parameters(request);
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

	/** Paramètres de la requête ; un nom ou une valeur refusés par le pare-feu donnent {@code INVALID_PARAMETER}. */
	private static Map<String, String[]> parameters(HttpServletRequest request) {
		try {
			return request.getParameterMap();
		}
		catch (RequestRejectedException ex) {
			// Pare-feu de Spring Security : nom ou valeur de paramètre refusé à la lecture. Son message porte la valeur
			// reçue : ni journalisé ni repris.
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre refusé.");
		}
	}

	/** Valeur du paramètre, {@code null} s'il est absent ; refusée si elle est répétée. */
	private static String text(Map<String, String[]> parameters, String name) {
		String[] values = parameters.get(name);
		if (values == null) {
			return null;
		}
		if (values.length != 1) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre répété : " + name + ".");
		}
		return values[0];
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

	@GetMapping("/snapshot")
	@RequiresScope("catalog:read")
	@Operation(operationId = "getCatalogSnapshot", summary = "Instantané paginé du catalogue lié (réconciliation, reprise)",
			description = "Sections dans l'ordre categories, products, productImages, availability ; limit compte les objets "
					+ "toutes sections confondues ; asOfSeq fixé à la première page, puis reprendre le flux avec "
					+ "after=asOfSeq. Disponibilité seulement avec la portée stock:read : sans elle, le champ availability est "
					+ "absent de chaque page et l'instantané s'arrête après productImages. Jeton de page opaque, lié à la "
					+ "liaison, valable 24 h. Limite : 60 appels par minute et par liaison (configurable).",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			parameters = {
					@Parameter(name = "pageToken", in = ParameterIn.QUERY,
							description = "Absent pour la première page, puis le nextPageToken reçu",
							schema = @Schema(type = "string", maxLength = 512)),
					@Parameter(name = "limit", in = ParameterIn.QUERY,
							description = "Nombre maximal d'objets de la page, toutes sections confondues (200 par défaut)",
							schema = @Schema(type = "integer", minimum = "1", maximum = "500", defaultValue = "200")) },
			responses = { @ApiResponse(responseCode = "200", description = "Page de l'instantané"),
					@ApiResponse(responseCode = "400", description = "INVALID_PAGE_TOKEN (jeton illisible, altéré, trop long ou "
							+ "d'une autre liaison), INVALID_PARAMETER (paramètre répété, limit non entier), VALIDATION_FAILED "
							+ "(limit hors bornes)",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "410", description = "SNAPSHOT_EXPIRED : jeton de plus de 24 heures",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED : plus de 60 appels par minute pour la "
							+ "liaison (valeur de départ, configurable) ; en-tête Retry-After",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	SnapshotPageView snapshot(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			HttpServletRequest request) {
		limit("catalog-snapshot:link:" + principal.linkId(), this.rateLimits.rateLimit().catalogSnapshotPerLink());
		Map<String, String[]> parameters = parameters(request);
		String pageToken = text(parameters, "pageToken");
		Long limit = integer(parameters, "limit");
		if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
			throw new OutOfBounds("limit", limit < 1 ? "doit être supérieur ou égal à 1"
					: "doit être inférieur ou égal à " + MAX_LIMIT);
		}
		// v17-D1 : la disponibilité seulement avec stock:read ; sinon, champ absent et fin après productImages.
		boolean withAvailability = principal.hasScope(LinkChangeFilters.STOCK_READ);
		SnapshotPage page = this.snapshots.read(new LinkRef(principal.linkId()), pageToken,
				limit != null ? Math.toIntExact(limit) : null, withAvailability);
		return SnapshotPageView.of(page, withAvailability);
	}

	@PostMapping("/changes/ack")
	@RequiresScope("catalog:read")
	@Operation(operationId = "acknowledgeCatalogChanges", summary = "Acquittement du curseur appliqué (condition de purge, D5)",
			description = "Acquittement de la liaison du jeton, enregistré dans la base de son entreprise : jamais en recul "
					+ "(un seq inférieur ou égal, même sous le repère de purge, répond 200 avec la valeur enregistrée) ; "
					+ "seq supérieur au dernier seq attribué : 409 CURSOR_AHEAD. Corps strict {\"seq\": entier de 0 à 2^63 - 1}. "
					+ "Limite : 60 acquittements par minute et par liaison (configurable), comptés avant la lecture du corps.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = AcknowledgementRequest.class))),
			responses = { @ApiResponse(responseCode = "200", description = "Curseur acquitté ; valeur enregistrée"),
					@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (seq absent, nul ou négatif), "
							+ "MALFORMED_REQUEST (seq d'un autre type, corps illisible)",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "409", description = "CURSOR_AHEAD : seq dépasse le dernier seq attribué",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "413", description = "CONTENT_TOO_LARGE : corps de plus de 1 024 octets",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE : corps autre que application/json",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED : plus de 60 acquittements par minute pour "
							+ "la liaison (valeur de départ, configurable) ; en-tête Retry-After",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	AcknowledgementView acknowledge(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			HttpServletRequest request) {
		// v17-D14 : limite d'abord ; un appel refusé n'est ni lu ni enregistré.
		limit("catalog-ack:link:" + principal.linkId(), this.rateLimits.rateLimit().catalogAckPerLink());
		if (!isJson(request.getContentType())) {
			throw new InvalidRequestException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
		}
		long seq = acknowledgedSeq(request);
		return new AcknowledgementView(this.acknowledgements.acknowledge(principal.linkId(), seq));
	}

	/** Compte l'appel sous {@code key} ; au-delà de {@code limit} dans la fenêtre : {@code 429} avec {@code Retry-After}. */
	private void limit(String key, int limit) {
		RateLimiter.Decision decision = this.limiter.hit(key, limit, this.rateLimits.rateLimit().window());
		if (!decision.allowed()) {
			log.info("Limitation de débit : {} ({} / {}) ⇒ 429, Retry-After {} s.", decision.key(), decision.hits(),
					decision.limit(), decision.retryAfterSeconds());
			throw new ChangesRateLimited(decision.retryAfterSeconds());
		}
	}

	private static boolean isJson(String contentType) {
		if (contentType == null) {
			return false;
		}
		try {
			return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(contentType));
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

	/**
	 * {@code seq} du corps, lu strictement (v17-D12), sans conversion implicite : objet JSON seul, clés uniques ; absent,
	 * {@code null} ou négatif : {@code VALIDATION_FAILED} ; tout autre type qu'un entier JSON sur 64 bits, ou corps
	 * illisible : {@code MALFORMED_REQUEST} ; plus de {@link #MAX_ACK_BODY} octets : {@code 413 CONTENT_TOO_LARGE} (choix
	 * E2). Aucun message ne reprend la valeur reçue ; aucune exception de lecture
	 * n'est journalisée.
	 */
	private long acknowledgedSeq(HttpServletRequest request) {
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(MAX_ACK_BODY + 1);
		}
		catch (IOException ex) {
			throw malformed();
		}
		if (body.length > MAX_ACK_BODY) {
			// RFC 9110 § 15.5.14 : 413, comme FILE_TOO_LARGE pour les fichiers ; le reste du corps n'est pas lu.
			throw new InvalidRequestException(ErrorCode.CONTENT_TOO_LARGE, null);
		}
		JsonNode root;
		try {
			root = this.strictJson.readTree(body);
		}
		catch (JacksonException ex) {
			throw malformed();
		}
		if (root == null || !root.isObject()) {
			throw malformed();
		}
		JsonNode seq = root.get("seq");
		if (seq == null || seq.isNull()) {
			throw new OutOfBounds("seq", "ne doit pas être nul");
		}
		if (!seq.isIntegralNumber() || !seq.canConvertToLong()) {
			throw malformed();
		}
		long value = seq.longValue();
		if (value < 0) {
			throw new OutOfBounds("seq", "doit être supérieur ou égal à 0");
		}
		return value;
	}

	private static InvalidRequestException malformed() {
		return new InvalidRequestException(ErrorCode.MALFORMED_REQUEST, null);
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
		log.warn("Limitation de débit indisponible ⇒ 503, lecture ou acquittement du flux refusé : {}.",
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

	/**
	 * Corps de {@code POST /catalog/changes/ack}, pour la documentation seulement : le corps est lu strictement par la
	 * route ({@link #acknowledgedSeq}).
	 *
	 * @param seq dernier {@code seq} dont les événements sont appliqués par la boutique
	 */
	@Schema(name = "IntegrationAcknowledgementRequest")
	record AcknowledgementRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", format = "int64") long seq) {
	}

	/**
	 * Réponse {@code 200} de {@code POST /catalog/changes/ack}.
	 *
	 * @param acknowledgedSeq valeur enregistrée après l'appel, jamais inférieure à la précédente
	 */
	@Schema(name = "IntegrationAcknowledgement")
	record AcknowledgementView(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long acknowledgedSeq) {
	}

	/**
	 * Réponse {@code 200} de {@code GET /catalog/snapshot} (contrat {@code SnapshotPage}).
	 *
	 * @param availability  disponibilités de la page ; <b>absent</b> (champ omis) sans la portée {@code stock:read} : non
	 *                      autorisé, à ne pas confondre avec une section vide (v17-D1, décision du 2026-11-03)
	 * @param nextPageToken absent sur la dernière page
	 */
	@Schema(name = "IntegrationSnapshotPage")
	record SnapshotPageView(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long asOfSeq,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<JsonNode> categories,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<JsonNode> products,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<JsonNode> productImages,
			@JsonInclude(JsonInclude.Include.NON_NULL) @Schema(description = "Absent sans la portée stock:read")
			List<JsonNode> availability,
			@JsonInclude(JsonInclude.Include.NON_NULL) String nextPageToken) {

		static SnapshotPageView of(SnapshotPage page, boolean withAvailability) {
			return new SnapshotPageView(page.asOfSeq(), page.currency(), page.categories(), page.products(),
					page.productImages(), withAvailability ? page.availability() : null, page.nextPageToken());
		}

		@Override
		public String toString() {
			return "SnapshotPageView[asOfSeq=" + this.asOfSeq + "]";
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
