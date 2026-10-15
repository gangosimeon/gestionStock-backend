package com.geststock.config;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.method.HandlerMethod;

import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.HandlerPermissions;
import com.geststock.exception.ErrorCode;
import com.geststock.warehouse.CurrentWarehouse;

/**
 * Compléments du document OpenAPI, déduits du code pour rester cohérents avec lui :
 * <ul>
 * <li>identifiants d'opération stables ({@code createProduct}, {@code salesReport}…), utilisés
 * comme noms de méthodes par les générateurs de clients Angular ;</li>
 * <li>permission(s) exigée(s), lue(s) sur {@code @RequiresPermission} / {@code @PreAuthorize} :
 * extension {@code x-permissions} et rappel dans la description ;</li>
 * <li>en-tête {@code X-Warehouse-Id} sur les routes qui dépendent du dépôt courant, et règle
 * commune du paramètre {@code allWarehouses} (vue tous dépôts) ;</li>
 * <li>réponses d'erreur standard ({@code ApiError}) avec exemples, et catalogue des codes
 * d'erreur généré depuis {@link ErrorCode} ;</li>
 * <li>exemples de requête et de réponse lus dans {@code classpath:openapi/examples/{operationId}.json}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class OpenApiCustomization {

	static final String PERMISSIONS_EXTENSION = "x-permissions";

	static final String WAREHOUSE_HEADER = "X-Warehouse-Id";

	static final String ALL_WAREHOUSES_PARAMETER = "allWarehouses";

	/** Même règle partout ({@code WarehouseAccess#seesAllWarehouses}) : une seule description. */
	static final String ALL_WAREHOUSES_DESCRIPTION = "true : tous les dépôts au lieu du dépôt courant. Exige la "
			+ "permission warehouse:all, sinon 403 WAREHOUSE_ACCESS_DENIED.";

	private static final String ERROR_REF = "#/components/schemas/ApiError";

	/**
	 * Noms explicites quand la règle « méthode + ressource » donne un nom peu lisible
	 * ({@code Contrôleur#méthode} → identifiant).
	 */
	static final Map<String, String> OPERATION_NAMES = Map.ofEntries(
			Map.entry("HealthController#health", "health"),
			Map.entry("AuthController#login", "login"),
			Map.entry("AuthController#refresh", "refreshToken"),
			Map.entry("AuthController#logout", "logout"),
			Map.entry("AuthController#me", "getCurrentUser"),
			Map.entry("AuthController#changePassword", "changeOwnPassword"),
			Map.entry("AuthController#forgotPassword", "forgotPassword"),
			Map.entry("StockController#create", "createStockMovement"),
			Map.entry("StockController#movements", "listStockMovements"),
			Map.entry("StockController#movement", "getStockMovement"),
			Map.entry("StockController#reverse", "reverseStockMovement"),
			Map.entry("StockController#levels", "listStockLevels"),
			Map.entry("StockTransferController#transfer", "transferStock"),
			Map.entry("SupplierPurchaseHistoryController#history", "listSupplierPurchases"),
			Map.entry("CustomerSalesController#list", "listCustomerSales"),
			Map.entry("CustomerController#account", "getCustomerAccount"),
			Map.entry("CustomerController#entries", "listCustomerAccountEntries"),
			Map.entry("CustomerPaymentController#list", "listCustomerPayments"),
			Map.entry("CustomerPaymentController#pay", "createCustomerPayment"),
			Map.entry("CustomerReturnController#forSale", "listSaleReturns"),
			Map.entry("CashRegisterController#current", "getCurrentCashSession"),
			Map.entry("CashRegisterController#open", "openCashSession"),
			Map.entry("CashRegisterController#close", "closeCashSession"),
			Map.entry("CashRegisterController#operation", "createCashOperation"),
			Map.entry("CashRegisterController#reverse", "reverseCashOperation"),
			Map.entry("CashRegisterController#sessions", "listCashSessions"),
			Map.entry("CashRegisterController#session", "getCashSession"),
			Map.entry("CashRegisterController#operations", "listCashSessionOperations"),
			Map.entry("InventoryController#counts", "recordInventoryCounts"),
			Map.entry("PurchaseOrderController#receipts", "listPurchaseOrderReceipts"),
			Map.entry("AlertController#settings", "getAlertSettings"),
			Map.entry("AlertController#updateSettings", "updateAlertSettings"),
			Map.entry("AlertController#summary", "getAlertSummary"),
			Map.entry("AuditLogController#stats", "getAuditLogStats"),
			Map.entry("DashboardController#summary", "getDashboardSummary"),
			Map.entry("DashboardController#salesByDay", "getDashboardSalesByDay"),
			Map.entry("DashboardController#topProducts", "getDashboardTopProducts"),
			Map.entry("DashboardController#stockEvolution", "getDashboardStockEvolution"),
			Map.entry("ReportController#movements", "stockMovementsReport"),
			Map.entry("ReportController#movementsByType", "stockMovementsByTypeReport"),
			Map.entry("WarehouseController#current", "getCurrentWarehouse"));

	// --- Opérations ---------------------------------------------------------------------------------

	@Bean
	OperationCustomizer gestStockOperationCustomizer() {
		return (operation, handler) -> {
			operation.setOperationId(operationId(handler));
			Arrays.stream(handler.getMethodParameters())
				.filter(p -> p.hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class))
				.forEach(p -> this.requestBodies.put(schemaName(p.getParameterType()), p.getParameterType()));
			List<String> permissions = permissions(handler);
			if (permissions != null) {
				operation.addExtension(PERMISSIONS_EXTENSION, permissions);
				String rule = permissions.isEmpty() ? "**Accès** : tout utilisateur connecté."
						: permissions.size() == 1 ? "**Permission** : `" + permissions.get(0) + "`."
								: "**Permission** : une parmi `" + String.join("`, `", permissions) + "`.";
				operation.setDescription(operation.getDescription() == null ? rule
						: operation.getDescription() + "\n\n" + rule);
			}
			if (Arrays.stream(handler.getMethodParameters())
				.anyMatch(p -> p.hasParameterAnnotation(CurrentWarehouse.class))) {
				operation.addParametersItem(new HeaderParameter().name(WAREHOUSE_HEADER)
					.required(false)
					.description("Dépôt courant (identifiant). Absent : dépôt par défaut de l'utilisateur, sinon "
							+ "dépôt principal (403 NO_WAREHOUSE_AVAILABLE si aucun n'est utilisable). Jamais "
							+ "remplacé par un autre : 400 INVALID_WAREHOUSE_ID (pas un UUID), "
							+ "400 UNKNOWN_WAREHOUSE (inconnu), 403 WAREHOUSE_ACCESS_DENIED (hors périmètre), "
							+ "403 WAREHOUSE_INACTIVE (désactivé).")
					.schema(new UUIDSchema()));
			}
			if (operation.getParameters() != null) {
				operation.getParameters()
					.stream()
					.filter(p -> ALL_WAREHOUSES_PARAMETER.equals(p.getName()))
					.forEach(p -> p.setDescription(ALL_WAREHOUSES_DESCRIPTION));
			}
			return operation;
		};
	}

	/** {@code create} + {@code ProductController} → {@code createProduct} ; {@code sales} + {@code ReportController} → {@code salesReport}. */
	static String operationId(HandlerMethod handler) {
		io.swagger.v3.oas.annotations.Operation explicit = handler
			.getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
		if (explicit != null && !explicit.operationId().isBlank()) {
			return explicit.operationId();
		}
		String named = OPERATION_NAMES.get(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName());
		if (named != null) {
			return named;
		}
		String resource = handler.getBeanType().getSimpleName().replace("Controller", "");
		return handler.getMethod().getName() + resource;
	}

	/** Permissions exigées (source unique : {@link HandlerPermissions}). */
	static List<String> permissions(HandlerMethod handler) {
		return HandlerPermissions.of(handler);
	}

	// --- Contraintes de validation ------------------------------------------------------------------

	/** Corps de requête rencontrés (nom de schéma → type), complétés une fois le document assemblé. */
	private final Map<String, Class<?>> requestBodies = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * {@code @NotEmpty} / {@code @NotBlank} combinés à {@code @Size(max = …)} : springdoc retient le
	 * {@code min = 0} implicite de {@code @Size}. On rétablit 1 élément / 1 caractère minimum, conforme
	 * à la validation réelle, en parcourant aussi les records imbriqués (lignes).
	 */
	@SuppressWarnings({ "rawtypes" })
	static void requireNonEmpty(Class<?> type, Schema schema, Map<String, Schema> schemas, Set<Class<?>> seen) {
		if (schema == null || !type.isRecord() || !seen.add(type) || schema.getProperties() == null) {
			return;
		}
		for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
			java.lang.reflect.Field field;
			try {
				field = type.getDeclaredField(component.getName());
			}
			catch (NoSuchFieldException ex) {
				continue;
			}
			Schema property = (Schema) schema.getProperties().get(component.getName());
			if (property == null) {
				continue;
			}
			boolean notEmpty = field.isAnnotationPresent(jakarta.validation.constraints.NotEmpty.class);
			boolean notBlank = field.isAnnotationPresent(jakarta.validation.constraints.NotBlank.class);
			if (notEmpty && isType(property, "array")
					&& (property.getMinItems() == null || property.getMinItems() < 1)) {
				property.setMinItems(1);
			}
			if ((notEmpty || notBlank) && isType(property, "string")
					&& (property.getMinLength() == null || property.getMinLength() < 1)) {
				property.setMinLength(1);
			}
			Class<?> nested = component.getType();
			Schema target = property;
			if (java.util.List.class.isAssignableFrom(nested)
					&& component.getGenericType() instanceof java.lang.reflect.ParameterizedType list
					&& list.getActualTypeArguments()[0] instanceof Class<?> item) {
				nested = item;
				target = property.getItems();
			}
			if (nested.isRecord() && target != null && target.get$ref() != null) {
				String name = target.get$ref().substring(target.get$ref().lastIndexOf('/') + 1);
				requireNonEmpty(nested, schemas.get(name), schemas, seen);
			}
		}
	}

	/** Type d'un schéma, en OpenAPI 3.0 ({@code type}) comme 3.1 ({@code types}). */
	@SuppressWarnings("rawtypes")
	private static boolean isType(Schema schema, String type) {
		return type.equals(schema.getType()) || (schema.getTypes() != null && schema.getTypes().contains(type));
	}

	static String schemaName(Class<?> type) {
		io.swagger.v3.oas.annotations.media.Schema annotation = type
			.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
		return annotation != null && !annotation.name().isBlank() ? annotation.name() : type.getSimpleName();
	}

	// --- Document -----------------------------------------------------------------------------------

	@Bean
	OpenApiCustomizer gestStockOpenApiCustomizer(JsonMapper json) {
		return openApi -> {
			// Lus à la génération du document (jamais au démarrage : rien à charger si la doc est désactivée).
			Map<String, Map<String, Object>> examples = loadExamples(json);
			Components components = openApi.getComponents();
			describeErrors(components);
			Set<Class<?>> seen = new java.util.HashSet<>();
			this.requestBodies.forEach((name, type) -> requireNonEmpty(type,
					components.getSchemas() != null ? components.getSchemas().get(name) : null, components.getSchemas(),
					seen));
			for (Map.Entry<String, PathItem> path : openApi.getPaths().entrySet()) {
				for (Map.Entry<PathItem.HttpMethod, Operation> entry : path.getValue().readOperationsMap().entrySet()) {
					Operation operation = entry.getValue();
					addErrorResponses(path.getKey(), entry.getKey(), operation);
					applyExamples(operation, examples.get(operation.getOperationId()));
				}
			}
			// Tags triés, décrits par leur nom (une entrée par contrôleur).
			TreeMap<String, Tag> tags = new TreeMap<>();
			openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op -> {
				if (op.getTags() != null) {
					op.getTags().forEach(name -> tags.putIfAbsent(name, new Tag().name(name)));
				}
			}));
			if (openApi.getTags() != null) {
				openApi.getTags().forEach(tag -> tags.put(tag.getName(), tag));
			}
			openApi.setTags(new ArrayList<>(tags.values()));
		};
	}

	/** Schéma {@code ApiError} : codes possibles en énumération, réponses d'erreur réutilisables. */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	private static void describeErrors(Components components) {
		Schema apiError = components.getSchemas() != null ? components.getSchemas().get("ApiError") : null;
		if (apiError != null && apiError.getProperties() != null) {
			// Toujours renseignés par le serveur (traceId seul peut manquer hors requête HTTP).
			apiError.setRequired(List.of("type", "title", "status", "code", "message", "detail", "errors", "details",
					"timestamp", "path"));
			Schema code = (Schema) apiError.getProperties().get("code");
			if (code != null) {
				code.setEnum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
				code.setDescription("Code d'erreur stable, à utiliser par le front (catalogue : description de l'API).");
			}
		}
		components.addResponses("BadRequest", error("Requête invalide : validation, paramètre ou corps illisible.",
				ErrorCode.VALIDATION_FAILED, "Données invalides : quantity doit être supérieur ou égal à 1",
				List.of(Map.of("field", "items[0].quantity", "message", "doit être supérieur ou égal à 1"))));
		components.addResponses("Unauthorized", error("Jeton absent, invalide ou expiré : renouveler la session "
				+ "(POST /api/auth/refresh) ou se reconnecter.", ErrorCode.INVALID_TOKEN, null, List.of()));
		components.addResponses("Forbidden", error("Permission manquante (voir x-permissions) ou dépôt non accessible.",
				ErrorCode.ACCESS_DENIED, null, List.of()));
		components.addResponses("NotFound", error("Ressource introuvable (ou hors de vos dépôts).",
				ErrorCode.PRODUCT_NOT_FOUND, null, List.of()));
		components.addResponses("Conflict", error("Conflit d'état : doublon, transition interdite, modification "
				+ "concurrente (recharger puis réessayer).", ErrorCode.CONCURRENT_MODIFICATION, null, List.of()));
		components.addResponses("UnprocessableContent", error("Règle métier refusée (stock insuffisant, plafond de "
				+ "crédit…) ; `details` précise le contexte.", ErrorCode.STOCK_INSUFFICIENT,
				"Stock insuffisant pour MON-RB-001 : 1 disponible, 2 demandés.", List.of()));
		components.addResponses("InternalError", error("Erreur inattendue ; communiquer le traceId au support.",
				ErrorCode.INTERNAL_ERROR, null, List.of()));
	}

	private static ApiResponse error(String description, ErrorCode code, String message, List<Map<String, String>> errors) {
		Map<String, Object> example = new LinkedHashMap<>();
		example.put("type", "urn:geststock:error:" + code.name().toLowerCase(Locale.ROOT).replace('_', '-'));
		example.put("title", code.status().getReasonPhrase());
		example.put("status", code.status().value());
		example.put("code", code.name());
		String text = message != null ? message : code.defaultMessage();
		example.put("message", text);
		example.put("detail", text);
		example.put("errors", errors);
		example.put("details", Map.of());
		example.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
		example.put("timestamp", Instant.parse("2026-10-10T09:30:00Z").toString());
		example.put("path", "/api/...");
		return new ApiResponse().description(description)
			.content(new Content().addMediaType("application/json",
					new MediaType().schema(new Schema<>().$ref(ERROR_REF)).example(example)));
	}

	/** Réponses d'erreur selon la nature de l'opération (sans écraser celles déclarées explicitement). */
	private static void addErrorResponses(String path, PathItem.HttpMethod method, Operation operation) {
		ApiResponses responses = operation.getResponses() != null ? operation.getResponses() : new ApiResponses();
		boolean secured = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
		boolean hasInput = operation.getRequestBody() != null
				|| (operation.getParameters() != null && !operation.getParameters().isEmpty());
		boolean write = method != PathItem.HttpMethod.GET;
		if (hasInput) {
			responses.putIfAbsent("400", ref("BadRequest"));
		}
		if (secured) {
			responses.putIfAbsent("401", ref("Unauthorized"));
			responses.putIfAbsent("403", ref("Forbidden"));
		}
		if (path.contains("{")) {
			responses.putIfAbsent("404", ref("NotFound"));
		}
		if (write && secured) {
			responses.putIfAbsent("409", ref("Conflict"));
			responses.putIfAbsent("422", ref("UnprocessableContent"));
		}
		responses.putIfAbsent("500", ref("InternalError"));
		operation.setResponses(responses);
	}

	private static ApiResponse ref(String name) {
		return new ApiResponse().$ref("#/components/responses/" + name);
	}

	// --- Exemples -----------------------------------------------------------------------------------

	/** Fichiers {@code openapi/examples/{operationId}.json} : {@code {"request": …, "response": …}}. */
	@SuppressWarnings("unchecked")
	static Map<String, Map<String, Object>> loadExamples(JsonMapper json) {
		Map<String, Map<String, Object>> examples = new TreeMap<>();
		try {
			Resource[] files = new PathMatchingResourcePatternResolver()
				.getResources("classpath*:openapi/examples/*.json");
			for (Resource file : files) {
				String name = file.getFilename();
				try (InputStream in = file.getInputStream()) {
					examples.put(name.substring(0, name.length() - ".json".length()), json.readValue(in, Map.class));
				}
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Exemples OpenAPI illisibles", ex);
		}
		return examples;
	}

	private static void applyExamples(Operation operation, Map<String, Object> example) {
		if (example == null) {
			return;
		}
		Object request = example.get("request");
		if (request != null && operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
			operation.getRequestBody().getContent().values().forEach(media -> media.setExample(request));
		}
		Object response = example.get("response");
		if (response != null && operation.getResponses() != null) {
			operation.getResponses().entrySet().stream()
				.filter(e -> e.getKey().startsWith("2"))
				.findFirst()
				.map(Map.Entry::getValue)
				.filter(r -> r.getContent() != null)
				.ifPresent(r -> r.getContent().values().forEach(media -> media.setExample(response)));
		}
	}

	/** Catalogue des codes d'erreur, pour la description générale de l'API. */
	static String errorCatalog() {
		StringBuilder table = new StringBuilder("| Code | HTTP | Message par défaut |\n|---|---|---|\n");
		for (ErrorCode code : ErrorCode.values()) {
			table.append("| `").append(code.name()).append("` | ").append(code.status().value()).append(" | ")
				.append(code.defaultMessage().replace("|", "\\|")).append(" |\n");
		}
		return table.toString();
	}

}
