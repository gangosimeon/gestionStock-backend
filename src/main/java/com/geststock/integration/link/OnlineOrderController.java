package com.geststock.integration.link;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.geststock.config.OpenApiConfig;
import com.geststock.exception.ApiError;
import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.integration.LinkRef;
import com.geststock.integration.OnlineOrders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
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
 * Commandes en ligne reçues des boutiques liées (lot 3, étape G28 ;
 * contrat {@code POST /online-orders}, portée {@code orders:write} ;
 * {@code GET /online-orders/{orderId}} et {@code GET /online-orders/external/{externalOrderId}},
 * portée {@code orders:read} ; {@code POST /online-orders/{orderId}/cancel}, portée {@code orders:write}).
 *
 * <p>Création : limite de débit (60/min), type de contenu, lecture stricte du corps, validation des champs,
 * puis {@link OnlineOrders#create}. Idempotence par (link_id, external_order_id) : même corps → 200,
 * corps différent → 409 IDEMPOTENCY_KEY_REUSED.
 *
 * <p>Lecture : par UUID interne ou par externalOrderId ; appartenance à la liaison vérifiée.
 *
 * <p>Annulation : CONVERTED → 409 ORDER_NOT_CANCELLABLE ; RESERVED → réservation annulée ; idempotent
 * pour CANCELLED/EXPIRED.
 */
@RestController
@RequestMapping(IntegrationSecurityConfig.BASE_PATH + "/online-orders")
@Tag(name = "Intégration JeCréeMaBoutique", description = "Routes du client d'intégration (contrat integration-v1)")
class OnlineOrderController {

	private static final Logger log = LoggerFactory.getLogger(OnlineOrderController.class);

	private static final String API_ERROR = "#/components/schemas/ApiError";

	/** Identifiant sous forme canonique 8-4-4-4-12. */
	private static final Pattern CANONICAL_UUID = Pattern
		.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	/** Prix positif, 12 chiffres et 2 décimales au plus. */
	private static final Pattern PRICE = Pattern.compile("[0-9]{1,12}(\\.[0-9]{1,2})?");

	/** Devise ISO 4217. */
	private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

	/** Taille maximale du corps d'une commande (100 lignes × ~200 octets + en-têtes). */
	static final int MAX_ORDER_BODY = 65536;

	private static final int MAX_LINES = 100;

	private final OnlineOrders orders;

	private final LinkRegistry links;

	private final RateLimiter limiter;

	private final IntegrationRateLimitProperties rateLimits;

	private final ApiErrorFactory errors;

	/** Lecture stricte : clés répétées et contenu après l'objet refusés. */
	private final ObjectReader strictJson;

	OnlineOrderController(OnlineOrders orders, LinkRegistry links, RateLimiter limiter,
			IntegrationRateLimitProperties rateLimits, ApiErrorFactory errors, JsonMapper json) {
		this.orders = orders;
		this.links = links;
		this.limiter = limiter;
		this.rateLimits = rateLimits;
		this.errors = errors;
		this.strictJson = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	}

	@PostMapping
	@RequiresScope("orders:write")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(operationId = "createOnlineOrder",
			summary = "Créer une commande en ligne (idempotente par externalOrderId)",
			description = "Enregistre une commande de la boutique. Idempotente par (liaison, externalOrderId) : "
					+ "même corps → 200 ; corps différent → 409 IDEMPOTENCY_KEY_REUSED. "
					+ "Produit inexistant : 404 PRODUCT_NOT_FOUND ; archivé : 422 PRODUCT_ARCHIVED ; "
					+ "sans prix approuvé : 422 PRODUCT_NOT_LINKED ; version ou prix erronés : 422. "
					+ "Stock insuffisant : statut REJECTED_STOCK (sans réservation). "
					+ "Limite : 60 créations par minute et par liaison (configurable).",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			responses = {
					@ApiResponse(responseCode = "201", description = "Commande créée",
							content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = OnlineOrderResponse.class))),
					@ApiResponse(responseCode = "200", description = "Requête rejouée : réponse enregistrée",
							content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = OnlineOrderResponse.class))),
					@ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST, INVALID_PARAMETER",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_REUSED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "413", description = "CONTENT_TOO_LARGE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "422",
							description = "PRODUCT_ARCHIVED, PRODUCT_NOT_LINKED, PRICE_VERSION_MISMATCH, "
									+ "PRICE_NOT_APPROVED, PRICE_CURRENCY_MISMATCH",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	ResponseEntity<OnlineOrderResponse> createOrder(
			@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			HttpServletRequest request) {
		limit("online-orders:link:" + principal.linkId(), this.rateLimits.rateLimit().onlineOrderPerLink());
		if (!CatalogChangesController.isJson(request.getContentType())) {
			throw new InvalidRequestException(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
		}
		OnlineOrders.OnlineOrderRequest orderRequest = readOrderRequest(request);
		String currency = currency(principal.linkId());
		OnlineOrders.CreateResult result = this.orders.create(new LinkRef(principal.linkId()), currency, orderRequest);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
			.body(toResponse(result.view()));
	}

	@GetMapping("/{orderId}")
	@RequiresScope("orders:read")
	@Operation(operationId = "getOnlineOrder", summary = "Lecture d'une commande en ligne par son identifiant",
			description = "Commande de la liaison du jeton ; appartenance vérifiée (404 si d'une autre liaison).",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			parameters = @Parameter(name = "orderId", in = ParameterIn.PATH, required = true,
					description = "Identifiant de la commande, forme canonique",
					schema = @Schema(type = "string", format = "uuid")),
			responses = {
					@ApiResponse(responseCode = "200", description = "Commande",
							content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = OnlineOrderResponse.class))),
					@ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (orderId non canonique)",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	OnlineOrderResponse getOrder(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			@PathVariable String orderId) {
		if (!CANONICAL_UUID.matcher(orderId).matches()) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre invalide : orderId.");
		}
		return this.orders.findById(new LinkRef(principal.linkId()), UUID.fromString(orderId))
			.map(this::toResponse)
			.orElseThrow(() -> new com.geststock.exception.NotFoundException(ErrorCode.ORDER_NOT_FOUND, null));
	}

	@GetMapping("/external/{externalOrderId}")
	@RequiresScope("orders:read")
	@Operation(operationId = "getOnlineOrderByExternalId",
			summary = "Lecture d'une commande en ligne par son identifiant externe",
			description = "Commande de la liaison du jeton identifiée par son externalOrderId.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			parameters = @Parameter(name = "externalOrderId", in = ParameterIn.PATH, required = true,
					description = "Identifiant externe de la commande (côté boutique)",
					schema = @Schema(type = "string", maxLength = 64)),
			responses = {
					@ApiResponse(responseCode = "200", description = "Commande",
							content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = OnlineOrderResponse.class))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	OnlineOrderResponse getOrderByExternalId(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			@PathVariable String externalOrderId) {
		return this.orders.findByExternalId(new LinkRef(principal.linkId()), externalOrderId)
			.map(this::toResponse)
			.orElseThrow(() -> new com.geststock.exception.NotFoundException(ErrorCode.ORDER_NOT_FOUND, null));
	}

	@PostMapping("/{orderId}/cancel")
	@RequiresScope("orders:write")
	@Operation(operationId = "cancelOnlineOrder", summary = "Annuler une commande en ligne",
			description = "Annule la commande de la liaison. CONVERTED → 409 ORDER_NOT_CANCELLABLE. "
					+ "RESERVED → réservation annulée. Déjà CANCELLED/EXPIRED → 200 (idempotent). "
					+ "Motif optionnel dans le corps.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			parameters = @Parameter(name = "orderId", in = ParameterIn.PATH, required = true,
					description = "Identifiant de la commande, forme canonique",
					schema = @Schema(type = "string", format = "uuid")),
			responses = {
					@ApiResponse(responseCode = "200", description = "Commande annulée",
							content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
									schema = @Schema(implementation = OnlineOrderResponse.class))),
					@ApiResponse(responseCode = "400", description = "INVALID_PARAMETER (orderId non canonique), "
							+ "MALFORMED_REQUEST",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403",
							description = "INSUFFICIENT_SCOPE, LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "409", description = "ORDER_NOT_CANCELLABLE : commande déjà convertie en vente",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	OnlineOrderResponse cancelOrder(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			@PathVariable String orderId, HttpServletRequest request) {
		if (!CANONICAL_UUID.matcher(orderId).matches()) {
			throw new InvalidRequestException(ErrorCode.INVALID_PARAMETER, "Paramètre invalide : orderId.");
		}
		String reason = readCancelReason(request);
		return toResponse(this.orders.cancel(new LinkRef(principal.linkId()), UUID.fromString(orderId), reason));
	}

	// ---- Lecture du corps ------------------------------------------------------------------

	/** Corps de la commande, lu strictement. */
	private OnlineOrders.OnlineOrderRequest readOrderRequest(HttpServletRequest request) {
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(MAX_ORDER_BODY + 1);
		}
		catch (IOException ex) {
			throw malformed();
		}
		if (body.length > MAX_ORDER_BODY) {
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

		// externalOrderId
		String externalOrderId = requiredText(root, "externalOrderId");
		if (ControlCharacters.present(externalOrderId, false)) {
			throw ControlCharacters.refused("externalOrderId");
		}
		if (externalOrderId.length() > 64) {
			throw new CatalogChangesController.OutOfBounds("externalOrderId", "doit faire au plus 64 caractères");
		}

		// createdAt
		JsonNode createdAtNode = root.get("createdAt");
		if (createdAtNode == null || createdAtNode.isNull()) {
			throw new CatalogChangesController.OutOfBounds("createdAt", "ne doit pas être nul");
		}
		if (!createdAtNode.isString()) {
			throw malformed();
		}
		Instant createdAt;
		try {
			createdAt = Instant.parse(createdAtNode.asString());
		}
		catch (Exception ex) {
			throw new CatalogChangesController.OutOfBounds("createdAt", "doit être une date-heure ISO 8601");
		}

		// currency
		String currency = requiredText(root, "currency");
		if (ControlCharacters.present(currency, false)) {
			throw ControlCharacters.refused("currency");
		}
		if (!CURRENCY.matcher(currency).matches()) {
			throw new CatalogChangesController.OutOfBounds("currency", "doit être un code ISO 4217 en majuscules");
		}

		// customer
		JsonNode customerNode = root.get("customer");
		if (customerNode == null || customerNode.isNull() || !customerNode.isObject()) {
			throw malformed();
		}
		String customerName = requiredText(customerNode, "name");
		if (ControlCharacters.present(customerName, false)) {
			throw ControlCharacters.refused("customer.name");
		}
		if (customerName.length() > 200) {
			throw new CatalogChangesController.OutOfBounds("customer.name", "doit faire au plus 200 caractères");
		}
		JsonNode phoneNode = customerNode.get("phone");
		String phone = null;
		if (phoneNode != null && !phoneNode.isNull()) {
			if (!phoneNode.isString()) {
				throw malformed();
			}
			phone = phoneNode.asString();
			if (ControlCharacters.present(phone, false)) {
				throw ControlCharacters.refused("customer.phone");
			}
			if (phone.length() > 30) {
				throw new CatalogChangesController.OutOfBounds("customer.phone", "doit faire au plus 30 caractères");
			}
		}

		// delivery
		JsonNode deliveryNode = root.get("delivery");
		if (deliveryNode == null || deliveryNode.isNull() || !deliveryNode.isObject()) {
			throw malformed();
		}
		String deliveryMode = requiredText(deliveryNode, "mode");
		if (ControlCharacters.present(deliveryMode, false)) {
			throw ControlCharacters.refused("delivery.mode");
		}
		if (!"pickup".equals(deliveryMode) && !"delivery".equals(deliveryMode)) {
			throw new CatalogChangesController.OutOfBounds("delivery.mode", "doit être pickup ou delivery");
		}
		String deliveryAddress = null;
		if ("delivery".equals(deliveryMode)) {
			deliveryAddress = requiredText(deliveryNode, "address");
			if (ControlCharacters.present(deliveryAddress, true)) { // @FreeText : tab/newline permis
				throw ControlCharacters.refused("delivery.address");
			}
			if (deliveryAddress.length() > 500) {
				throw new CatalogChangesController.OutOfBounds("delivery.address", "doit faire au plus 500 caractères");
			}
		}

		// note (optionnel, texte libre)
		String note = null;
		JsonNode noteNode = root.get("note");
		if (noteNode != null && !noteNode.isNull()) {
			if (!noteNode.isString()) {
				throw malformed();
			}
			note = noteNode.asString();
			if (ControlCharacters.present(note, true)) { // @FreeText : tab/newline permis
				throw ControlCharacters.refused("note");
			}
			if (note.length() > 500) {
				throw new CatalogChangesController.OutOfBounds("note", "doit faire au plus 500 caractères");
			}
		}

		// lines
		JsonNode linesNode = root.get("lines");
		if (linesNode == null || linesNode.isNull() || !linesNode.isArray()) {
			throw malformed();
		}
		if (linesNode.size() < 1) {
			throw new CatalogChangesController.OutOfBounds("lines", "doit contenir au moins 1 ligne");
		}
		if (linesNode.size() > MAX_LINES) {
			throw new CatalogChangesController.OutOfBounds("lines", "doit contenir au plus " + MAX_LINES + " lignes");
		}
		java.util.List<OnlineOrders.OnlineOrderRequest.OrderLine> lines = new java.util.ArrayList<>();
		for (JsonNode lineNode : linesNode) {
			if (!lineNode.isObject()) {
				throw malformed();
			}
			String productIdStr = requiredText(lineNode, "productId");
			if (!CANONICAL_UUID.matcher(productIdStr).matches()) {
				throw new CatalogChangesController.OutOfBounds("lines[].productId", "doit être un UUID canonique");
			}
			UUID productId = UUID.fromString(productIdStr);

			JsonNode qtyNode = lineNode.get("quantity");
			if (qtyNode == null || qtyNode.isNull()) {
				throw new CatalogChangesController.OutOfBounds("lines[].quantity", "ne doit pas être nul");
			}
			if (!qtyNode.isIntegralNumber() || !qtyNode.canConvertToInt()) {
				throw malformed();
			}
			int quantity = qtyNode.intValue();
			if (quantity < 1 || quantity > 9999) {
				throw new CatalogChangesController.OutOfBounds("lines[].quantity", "doit être entre 1 et 9999");
			}

			String unitPrice = requiredText(lineNode, "unitPrice");
			if (!PRICE.matcher(unitPrice).matches()) {
				throw new CatalogChangesController.OutOfBounds("lines[].unitPrice",
						"doit être un décimal positif, 12 chiffres et 2 décimales au plus");
			}

			JsonNode priceVersionNode = lineNode.get("priceVersion");
			if (priceVersionNode == null || priceVersionNode.isNull()) {
				throw new CatalogChangesController.OutOfBounds("lines[].priceVersion", "ne doit pas être nul");
			}
			if (!priceVersionNode.isIntegralNumber() || !priceVersionNode.canConvertToLong()) {
				throw malformed();
			}
			long priceVersion = priceVersionNode.longValue();

			String sku = null;
			JsonNode skuNode = lineNode.get("sku");
			if (skuNode != null && !skuNode.isNull()) {
				if (!skuNode.isString()) throw malformed();
				sku = skuNode.asString();
				if (ControlCharacters.present(sku, false)) {
					throw ControlCharacters.refused("lines[].sku");
				}
			}

			String label = null;
			JsonNode labelNode = lineNode.get("label");
			if (labelNode != null && !labelNode.isNull()) {
				if (!labelNode.isString()) throw malformed();
				label = labelNode.asString();
				if (ControlCharacters.present(label, false)) {
					throw ControlCharacters.refused("lines[].label");
				}
				if (label.length() > 200) {
					throw new CatalogChangesController.OutOfBounds("lines[].label", "doit faire au plus 200 caractères");
				}
			}

			lines.add(new OnlineOrders.OnlineOrderRequest.OrderLine(productId, quantity, unitPrice, priceVersion, sku, label));
		}

		return new OnlineOrders.OnlineOrderRequest(externalOrderId, createdAt, currency,
				new OnlineOrders.OnlineOrderRequest.Customer(customerName, phone),
				new OnlineOrders.OnlineOrderRequest.Delivery(deliveryMode, deliveryAddress),
				note, lines);
	}

	/** Motif d'annulation du corps (optionnel). */
	private String readCancelReason(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null || contentType.isBlank()) {
			return null; // corps absent
		}
		if (!CatalogChangesController.isJson(contentType)) {
			return null; // corps ignoré si non JSON
		}
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(4096);
		}
		catch (IOException ex) {
			return null;
		}
		if (body.length == 0) {
			return null;
		}
		try {
			JsonNode root = this.strictJson.readTree(body);
			if (root == null || root.isNull()) return null;
			if (!root.isObject()) return null;
			JsonNode reason = root.get("reason");
			if (reason == null || reason.isNull()) return null;
			if (!reason.isString()) throw malformed();
			String text = reason.asString();
			if (ControlCharacters.present(text, true)) { // motif libre
				throw ControlCharacters.refused("reason");
			}
			return text;
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	/** Devise de la liaison depuis la base plateforme. */
	private String currency(UUID linkId) {
		return this.links.find(linkId).orElseThrow().currency();
	}

	// ---- Utilitaires -----------------------------------------------------------------------

	/** Champ texte obligatoire. */
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

	/** Limite de débit. */
	private void limit(String key, int limit) {
		RateLimiter.Decision decision = this.limiter.hit(key, limit, this.rateLimits.rateLimit().window());
		if (!decision.allowed()) {
			log.info("Limitation de débit (commandes) : {} ({} / {}) ⇒ 429, Retry-After {} s.",
					decision.key(), decision.hits(), decision.limit(), decision.retryAfterSeconds());
			throw new CatalogChangesController.ChangesRateLimited(decision.retryAfterSeconds());
		}
	}

	/** Convertit la vue métier en réponse JSON. */
	private OnlineOrderResponse toResponse(OnlineOrders.OnlineOrderView view) {
		ReservationResponse reservation = null;
		if (view.reservationView() != null) {
			OnlineOrders.ReservationView rv = view.reservationView();
			List<AllocationResponse> allocations = rv.allocations().stream()
				.map(a -> new AllocationResponse(a.warehouseId(), a.productId(), a.quantity()))
				.toList();
			reservation = new ReservationResponse(rv.id(), rv.status(), rv.expiresAt(), allocations);
		}
		List<OrderLineResponse> lines = view.lineViews().stream()
			.map(l -> new OrderLineResponse(l.productId(), l.quantity(), l.unitPrice(), l.priceVersion(),
					l.sku(), l.label()))
			.toList();
		return new OnlineOrderResponse(view.orderId(), view.externalOrderId(), view.status(), view.cancelReason(),
				view.currency(), view.customerName(), view.customerPhone(), view.deliveryMode(),
				view.deliveryAddress(), view.note(), view.externalCreatedAt(), view.createdAt(), lines, reservation,
				null);
	}

	// ---- Gestionnaires d'exceptions --------------------------------------------------------

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

	@ExceptionHandler(RateLimiter.Unavailable.class)
	ResponseEntity<ApiError> rateLimitUnavailable(RateLimiter.Unavailable ex, HttpServletRequest request) {
		log.warn("Limitation de débit indisponible ⇒ 503, commande en ligne refusée : {}.",
				ex.getCause().getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
			.header(HttpHeaders.PRAGMA, "no-cache")
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.RATE_LIMIT_UNAVAILABLE, null, request));
	}

	// ---- Vues JSON -------------------------------------------------------------------------

	/** Réponse d'une commande en ligne. Données personnelles masquées dans {@code toString()} (G15). */
	@Schema(name = "OnlineOrder")
	record OnlineOrderResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID orderId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String externalOrderId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
			@JsonInclude(JsonInclude.Include.NON_NULL) String reason,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String customerName,
			@JsonInclude(JsonInclude.Include.NON_NULL) String customerPhone,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String deliveryMode,
			@JsonInclude(JsonInclude.Include.NON_NULL) String deliveryAddress,
			@JsonInclude(JsonInclude.Include.NON_NULL) String note,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time") Instant externalCreatedAt,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time") Instant createdAt,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<OrderLineResponse> lines,
			@JsonInclude(JsonInclude.Include.NON_NULL) ReservationResponse reservation,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<UUID> saleIds) {

		@Override
		public String toString() {
			return "OnlineOrderResponse[orderId=" + orderId + ", status=" + status + "]";
		}
	}

	/** Ligne d'une commande en ligne. */
	@Schema(name = "OnlineOrderLine")
	record OrderLineResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID productId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "9999") int quantity,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String unitPrice,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long priceVersion,
			@JsonInclude(JsonInclude.Include.NON_NULL) String sku,
			@JsonInclude(JsonInclude.Include.NON_NULL) String label) {
	}

	/** Réservation de stock d'une commande. */
	@Schema(name = "OnlineReservation")
	record ReservationResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID id,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time") Instant expiresAt,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AllocationResponse> allocations) {
	}

	/** Allocation d'une réservation. */
	@Schema(name = "ReservationAllocation")
	record AllocationResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID warehouseId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID productId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int quantity) {
	}

}
