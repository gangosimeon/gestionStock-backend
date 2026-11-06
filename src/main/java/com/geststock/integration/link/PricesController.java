package com.geststock.integration.link;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.api.SortWhitelist;

import com.geststock.config.OpenApiConfig;
import com.geststock.exception.ApiError;
import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.integration.ChannelPrice;
import com.geststock.integration.ChannelPrices;

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
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prix proposés par la boutique (lot 3, étapes G22a, G25 et G26 ; contrat {@code POST /prices/proposals} et
 * {@code GET /prices/proposals}, portée {@code price:propose} ; {@code GET /prices}, portée {@code catalog:read} ; plan §
 * 4 D2, C6). Les écritures et lectures ont lieu dans la base de l'entreprise de la liaison ({@link ChannelPrices}),
 * entreprise courante posée par la
 * chaîne d'intégration.
 *
 * <p>Proposition, dans l'ordre : limite de débit (60 par minute et par liaison, comptée avant toute lecture), type de
 * contenu ({@code 415}), en-tête {@code Idempotency-Key} (absent ou hors motif : {@code 400 INVALID_PARAMETER}), corps
 * lu strictement (clés répétées refusées ; type d'un champ faux ou corps illisible : {@code MALFORMED_REQUEST} ; valeur
 * absente ou hors bornes : {@code VALIDATION_FAILED}, champ dans {@code errors}), puis {@link ChannelPrices#propose}.
 * Le corps et les paramètres sont lus ici, et non liés par Spring MVC, dont la conversion cite la valeur refusée dans
 * ses journaux (G15) ; aucune valeur reçue n'est journalisée.
 *
 * <p>Lecture : limite de débit (120 par minute et par liaison), puis {@code productIds} : 1 à 100 identifiants
 * canoniques séparés par des virgules ; absent, vide, répété ou non canonique : {@code 400 INVALID_PARAMETER} ; plus
 * de 100 : {@code 400 VALIDATION_FAILED}.
 */
@RestController
@RequestMapping(IntegrationSecurityConfig.BASE_PATH + "/prices")
@Tag(name = "Intégration JeCréeMaBoutique", description = "Routes du client d'intégration (contrat integration-v1)")
class PricesController {

	private static final Logger log = LoggerFactory.getLogger(PricesController.class);

	private static final String API_ERROR = "#/components/schemas/ApiError";

	/** Identifiant sous forme canonique 8-4-4-4-12, hexadécimal sans distinction de casse (G19-D5). */
	private static final Pattern CANONICAL_UUID = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	/** Contrat : 8 à 100 caractères, lettres, chiffres, {@code . _ : -}. */
	private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,100}");

	/** Décimal positif en chaîne, 12 chiffres et 2 décimales au plus (colonne numeric(14,2)). */
	private static final Pattern PRICE = Pattern.compile("[0-9]{1,12}(\\.[0-9]{1,2})?");

	private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

	private static final int MAX_NOTE = 500;

	private static final int MAX_PRODUCTS = 100;

	/** Taille maximale du corps d'une proposition. */
	static final int MAX_PROPOSAL_BODY = 4096;

	/** Statuts acceptés sur {@code GET /prices/proposals} (G26). */
	private static final java.util.Set<String> PROPOSAL_STATUSES = java.util.Set.of("PROPOSED", "APPROVED", "REJECTED",
			"SUPERSEDED", "WITHDRAWN");

	/** Ordre fixe (les plus récentes d'abord) : aucun tri demandé par le client. */
	private static final SortWhitelist NO_SORT = new SortWhitelist(java.util.Map.of(), Sort.unsorted());

	private final ChannelPrices prices;

	private final LinkRegistry links;

	private final RateLimiter limiter;

	private final IntegrationRateLimitProperties rateLimits;

	private final ApiErrorFactory errors;

	/** Lecture stricte du corps : clés répétées et contenu après l'objet refusés (comme l'acquittement, v17-D12). */
	private final ObjectReader strictJson;

	PricesController(ChannelPrices prices, LinkRegistry links, RateLimiter limiter, IntegrationRateLimitProperties rateLimits,
			ApiErrorFactory errors, JsonMapper json) {
		this.prices = prices;
		this.links = links;
		this.limiter = limiter;
		this.rateLimits = rateLimits;
		this.errors = errors;
		this.strictJson = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	}

	@PostMapping("/proposals")
	@RequiresScope("price:propose")
	@Operation(operationId = "createPriceProposal", summary = "Proposer un prix de vente boutique (soumis à approbation, D2)",
			description = "Crée une proposition PROPOSED, ou renvoie la réponse enregistrée si la clé d'idempotence a déjà "
					+ "servi pour la même requête (200) ; même clé et autre requête : 409 IDEMPOTENCY_KEY_REUSED. Une seule "
					+ "proposition PROPOSED par (liaison, produit) : la précédente passe SUPERSEDED, la dernière qui obtient "
					+ "le verrou est retenue (C6). basedOnReferenceVersion différent de la version courante du prix de "
					+ "référence : 422 PRICE_VERSION_MISMATCH, aucune proposition créée. Seul le dernier prix approuvé dans "
					+ "GestStock est commercialisable. Limite : 60 propositions par minute et par liaison (configurable), "
					+ "comptées avant la lecture du corps.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
					description = "Unique par liaison et par opération ; conservée au moins 7 jours (jamais purgée)",
					schema = @Schema(type = "string", minLength = 8, maxLength = 100, pattern = "^[A-Za-z0-9._:-]+$")),
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PriceProposalRequest.class))),
			responses = { @ApiResponse(responseCode = "201", description = "Proposition enregistrée",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = ChannelPrice.class))),
					@ApiResponse(responseCode = "200", description = "Requête rejouée : réponse enregistrée",
							content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = ChannelPrice.class))),
					@ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (Idempotency-Key absent ou invalide), "
							+ "VALIDATION_FAILED (champ absent ou hors bornes), MALFORMED_REQUEST (type d'un champ, corps "
							+ "illisible, clé répétée)",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND : produit inexistant ou d'une autre "
							+ "entreprise (réponse identique)",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_REUSED : même clé, autre requête",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "413", description = "CONTENT_TOO_LARGE : corps de plus de 4 096 octets",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE : corps autre que application/json",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "422", description = "PRICE_VERSION_MISMATCH, PRICE_CURRENCY_MISMATCH, "
							+ "PRODUCT_ARCHIVED : aucune proposition créée",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED : plus de 60 propositions par minute pour "
							+ "la liaison (valeur de départ, configurable) ; en-tête Retry-After",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	ResponseEntity<JsonNode> propose(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			HttpServletRequest request) {
		// Limite d'abord : un appel refusé n'est ni lu ni enregistré.
		limit("price-proposals:link:" + principal.linkId(), this.rateLimits.rateLimit().priceProposalsPerLink());
		if (!CatalogChangesController.isJson(request.getContentType())) {
			throw new InvalidRequestException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
		}
		String key = request.getHeader("Idempotency-Key");
		if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "En-tête Idempotency-Key absent ou invalide.");
		}
		ChannelPrices.ProposalRequest proposal = proposal(request);
		String currency = this.links.find(principal.linkId()).orElseThrow().currency();
		ChannelPrices.Outcome outcome = this.prices.propose(principal.linkId(), currency, key, proposal);
		return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.contentType(MediaType.APPLICATION_JSON).body(outcome.body());
	}

	@DeleteMapping("/proposals/{proposalId}")
	@RequiresScope("price:propose")
	@Operation(operationId = "withdrawPriceProposal", summary = "Retirer une proposition de prix en attente (G25)",
			description = "La proposition, encore en attente (PROPOSED), passe WITHDRAWN. La boutique ne peut retirer que "
					+ "ses propres propositions. PRICE_PROPOSAL_NOT_FOUND (404) si la proposition est inconnue ou "
					+ "d'une autre liaison ; PRICE_PROPOSAL_NOT_PENDING (409) si elle a déjà été décidée ou remplacée. "
					+ "Un événement CHANNEL_PRICE est publié.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			responses = { @ApiResponse(responseCode = "200", description = "Proposition retirée"),
					@ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (proposalId invalide)",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "404", description = "PRICE_PROPOSAL_NOT_FOUND",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "409", description = "PRICE_PROPOSAL_NOT_PENDING : déjà décidée ou remplacée",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED : limite partagée avec les propositions",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	ChannelPrices.ProposalView withdraw(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			@PathVariable String proposalId) {
		limit("price-proposals:link:" + principal.linkId(), this.rateLimits.rateLimit().priceProposalsPerLink());
		if (!CANONICAL_UUID.matcher(proposalId).matches()) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre invalide : proposalId.");
		}
		return this.prices.withdraw(principal.linkId(), UUID.fromString(proposalId));
	}

	@GetMapping
	@RequiresScope("catalog:read")
	@Operation(operationId = "listPrices", summary = "Prix de la boutique (référence, proposé, approuvé) par produit lié",
			description = "Prix de la liaison du jeton pour chacun des produits demandés de l'entreprise liée (produits "
					+ "introuvables omis) : prix de référence et sa version, dernier prix approuvé (seul commercialisable), "
					+ "dernière proposition de cette liaison ; jamais les propositions d'une autre liaison. Limite : 120 "
					+ "appels par minute et par liaison (configurable).",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			parameters = @Parameter(name = "productIds", in = ParameterIn.QUERY, required = true,
					description = "1 à 100 identifiants de produits, forme canonique, séparés par des virgules",
					schema = @Schema(type = "string")),
			responses = { @ApiResponse(responseCode = "200", description = "Prix",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = PricesView.class))),
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
	PricesView prices(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			HttpServletRequest request) {
		limit("prices:link:" + principal.linkId(), this.rateLimits.rateLimit().pricesPerLink());
		Map<String, String[]> parameters = CatalogChangesController.parameters(request);
		String value = CatalogChangesController.text(parameters, "productIds");
		if (value == null || value.isEmpty()) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre requis : productIds.");
		}
		String[] parts = value.split(",", -1);
		if (parts.length > MAX_PRODUCTS) {
			throw new CatalogChangesController.OutOfBounds("productIds", "doit contenir au plus " + MAX_PRODUCTS
					+ " identifiants");
		}
		List<UUID> ids = new ArrayList<>(parts.length);
		for (String part : parts) {
			if (!CANONICAL_UUID.matcher(part).matches()) {
				throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre invalide : productIds.");
			}
			ids.add(UUID.fromString(part));
		}
		return new PricesView(this.prices.read(principal.linkId(), ids));
	}

	@GetMapping("/proposals")
	@RequiresScope("price:propose")
	@Operation(operationId = "listOwnPriceProposals", summary = "Propositions de prix de la liaison (G26)",
			description = "Propositions de la liaison du jeton dans le statut demandé (PROPOSED par défaut ; APPROVED, "
					+ "REJECTED, SUPERSEDED, WITHDRAWN), les plus récentes d'abord. Limite : 120 appels par minute et par "
					+ "liaison (configurable).",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			responses = { @ApiResponse(responseCode = "200", description = "Propositions",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OwnProposalsView.class))),
					@ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (statut invalide)",
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
	ResponseEntity<PageResponse<ChannelPrices.ProposalView>> listProposals(
			@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		limit("prices:link:" + principal.linkId(), this.rateLimits.rateLimit().pricesPerLink());
		String wanted = status != null ? status : "PROPOSED";
		if (!PROPOSAL_STATUSES.contains(wanted)) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER,
					"Paramètre invalide : status vaut PROPOSED, APPROVED, REJECTED, SUPERSEDED ou WITHDRAWN.");
		}
		PageQuery query = new PageQuery(page, size, null);
		return PageResponse.toResponse(this.prices.proposals(principal.linkId(), wanted, query.toPageable(NO_SORT)), query);
	}

	/** Compte l'appel sous {@code key} ; au-delà de {@code limit} dans la fenêtre : {@code 429} avec {@code Retry-After}. */
	private void limit(String key, int limit) {
		RateLimiter.Decision decision = this.limiter.hit(key, limit, this.rateLimits.rateLimit().window());
		if (!decision.allowed()) {
			log.info("Limitation de débit : {} ({} / {}) ⇒ 429, Retry-After {} s.", decision.key(), decision.hits(),
					decision.limit(), decision.retryAfterSeconds());
			throw new CatalogChangesController.ChangesRateLimited(decision.retryAfterSeconds());
		}
	}

	/**
	 * Proposition du corps, lue strictement, sans conversion implicite. Aucun message ne reprend une valeur reçue ;
	 * aucune exception de lecture n'est journalisée.
	 */
	private ChannelPrices.ProposalRequest proposal(HttpServletRequest request) {
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(MAX_PROPOSAL_BODY + 1);
		}
		catch (IOException ex) {
			throw malformed();
		}
		if (body.length > MAX_PROPOSAL_BODY) {
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
		String productId = requiredText(root, "productId");
		if (!CANONICAL_UUID.matcher(productId).matches()) {
			throw new CatalogChangesController.OutOfBounds("productId", "doit être un identifiant 8-4-4-4-12");
		}
		String price = requiredText(root, "price");
		BigDecimal amount = PRICE.matcher(price).matches() ? new BigDecimal(price) : null;
		if (amount == null || amount.signum() <= 0) {
			throw new CatalogChangesController.OutOfBounds("price",
					"doit être un décimal positif, 12 chiffres et 2 décimales au plus");
		}
		String currency = requiredText(root, "currency");
		if (!CURRENCY.matcher(currency).matches()) {
			throw new CatalogChangesController.OutOfBounds("currency", "doit être un code ISO 4217 en majuscules");
		}
		JsonNode version = root.get("basedOnReferenceVersion");
		if (version == null || version.isNull()) {
			throw new CatalogChangesController.OutOfBounds("basedOnReferenceVersion", "ne doit pas être nul");
		}
		if (!version.isIntegralNumber() || !version.canConvertToLong()) {
			throw malformed();
		}
		if (version.longValue() < 0) {
			throw new CatalogChangesController.OutOfBounds("basedOnReferenceVersion", "doit être supérieur ou égal à 0");
		}
		JsonNode note = root.get("note");
		String noteText = null;
		if (note != null && !note.isNull()) {
			if (!note.isString()) {
				throw malformed();
			}
			noteText = note.asString();
			if (noteText.length() > MAX_NOTE) {
				throw new CatalogChangesController.OutOfBounds("note", "doit faire au plus " + MAX_NOTE + " caractères");
			}
		}
		return new ChannelPrices.ProposalRequest(UUID.fromString(productId), amount, currency, version.longValue(), noteText);
	}

	/** Champ texte obligatoire : absent ou {@code null} : {@code VALIDATION_FAILED} ; autre type : {@code MALFORMED_REQUEST}. */
	private static String requiredText(JsonNode root, String field) {
		JsonNode value = root.get(field);
		if (value == null || value.isNull()) {
			throw new CatalogChangesController.OutOfBounds(field, "ne doit pas être nul");
		}
		if (!value.isString()) {
			throw malformed();
		}
		return value.asString();
	}

	private static InvalidRequestException malformed() {
		return new InvalidRequestException(ErrorCode.MALFORMED_REQUEST, null);
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
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.PRAGMA, "no-cache")
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.RATE_LIMITED, null, request));
	}

	/** Compteurs de débit inaccessibles : jamais de laisser-passer (v13-D4). */
	@ExceptionHandler(RateLimiter.Unavailable.class)
	ResponseEntity<ApiError> rateLimitUnavailable(RateLimiter.Unavailable ex, HttpServletRequest request) {
		log.warn("Limitation de débit indisponible ⇒ 503, proposition ou lecture des prix refusée : {}.",
				ex.getCause().getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
			.header(HttpHeaders.PRAGMA, "no-cache")
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.RATE_LIMIT_UNAVAILABLE, null, request));
	}

	/**
	 * Corps de {@code POST /prices/proposals}, pour la documentation seulement : le corps est lu strictement par la route.
	 *
	 * @param price                   décimal en chaîne, positif, 2 décimales au plus
	 * @param currency                devise de la liaison
	 * @param basedOnReferenceVersion version du prix de référence vue par le commerçant
	 */
	@Schema(name = "IntegrationPriceProposalRequest")
	record PriceProposalRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") String productId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9]{1,12}(\\.[0-9]{1,2})?$") String price,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[A-Z]{3}$") String currency,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", format = "int64") long basedOnReferenceVersion,
			@Schema(maxLength = MAX_NOTE) String note) {
	}

	/** Réponse {@code 200} de {@code GET /prices}. */
	@Schema(name = "IntegrationPrices")
	record PricesView(@ArraySchema(schema = @Schema(implementation = ChannelPrice.class)) List<ChannelPrice> items) {
	}

	/** Réponse {@code 200} de {@code GET /prices/proposals} (G26). */
	@Schema(name = "IntegrationOwnProposals")
	record OwnProposalsView(
			@ArraySchema(schema = @Schema(implementation = ChannelPrices.ProposalView.class)) List<ChannelPrices.ProposalView> items,
			long total, int page, int size) {
	}

}
