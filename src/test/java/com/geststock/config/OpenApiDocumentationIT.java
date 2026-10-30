package com.geststock.config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.exception.ErrorCode;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.warehouse.CurrentWarehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Document OpenAPI : export ({@code target/openapi/openapi.json}) et cohérence entre l'API
 * réelle (contrôleurs), les DTO, leur validation et la documentation. Un écart (route sans
 * permission documentée, champ obligatoire non signalé, exemple refusé par la validation…)
 * fait échouer le build.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class OpenApiDocumentationIT {

	/** Routes publiques, et seulement elles. */
	private static final Set<String> PUBLIC = Set.of("POST /api/auth/login", "POST /api/auth/refresh",
			"POST /api/auth/logout", "POST /api/auth/forgot-password", "POST /api/auth/activate",
			"POST /api/platform/auth/login", "POST /api/platform/auth/refresh", "POST /api/platform/auth/logout",
			"GET /api/public/companies/{code}/branding", "GET /api/public/companies/{code}/logo", "GET /api/health");

	/** Routes du client d'intégration (HTTP Basic, erreurs OAuth) : ni utilisateur, ni permission, ni 403. */
	private static final Set<String> INTEGRATION_CLIENT = Set.of("POST /api/integration/v1/oauth/par",
			"POST /api/integration/v1/oauth/token");

	/** Routes de la liaison (jeton de liaison, portée et non permission) : ni utilisateur, ni permission (G11, G14). */
	private static final Set<String> INTEGRATION_LINK = Set.of("GET /api/integration/v1/link",
			"POST /api/integration/v1/link/revoke", "POST /api/integration/v1/link/secret");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JsonMapper json;

	@Autowired
	private Validator validator;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping mappings;

	private JsonNode spec;

	private Map<String, JsonNode> operations;

	private Map<String, HandlerMethod> handlers;

	@BeforeEach
	void loadDocument() throws Exception {
		String content = this.mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		this.spec = this.json.readTree(content);
		this.operations = new HashMap<>();
		this.spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(op -> {
			this.operations.put(op.getKey().toUpperCase() + " " + path.getKey(), op.getValue());
		}));
		this.handlers = new HashMap<>();
		this.mappings.getHandlerMethods().forEach((info, handler) -> {
			if (info.getPatternValues().stream().anyMatch(pattern -> pattern.startsWith("/api/"))) {
				this.handlers.put(OpenApiCustomization.operationId(handler), handler);
			}
		});
	}

	@Test
	void exportsTheDocumentForTheFront() throws Exception {
		Path target = Path.of("target", "openapi", "openapi.json");
		Files.createDirectories(target.getParent());
		Files.writeString(target, this.json.writerWithDefaultPrettyPrinter().writeValueAsString(this.spec),
				StandardCharsets.UTF_8);
		assertThat(this.spec.get("info").get("description").asString()).contains("Authentification")
			.contains("Catalogue des codes d'erreur");
	}

	// --- Opérations ---------------------------------------------------------------------------------

	@Test
	void everyOperationHasAStableIdASummaryASuccessAndItsErrors() {
		List<String> ids = new ArrayList<>();
		this.operations.forEach((key, op) -> {
			ids.add(op.get("operationId").asString());
			assertThat(op.path("summary").asString()).as(key + " : résumé").isNotBlank();
			assertThat(op.get("tags")).as(key + " : tag").isNotEmpty();
			JsonNode responses = op.get("responses");
			assertThat(responses.propertyNames()).as(key + " : réponse de succès").anyMatch(code -> code.startsWith("2"));
			assertThat(responses.propertyNames()).as(key + " : erreur serveur").contains("500");
			boolean isPublic = PUBLIC.contains(key);
			assertThat(op.has("security") && op.get("security").isEmpty()).as(key + " : route publique").isEqualTo(isPublic);
			if (INTEGRATION_CLIENT.contains(key)) {
				assertThat(op.get("security").toString()).as(key + " : client d'intégration")
					.isEqualTo("[{\"integrationClient\":[]}]");
				assertThat(responses.propertyNames()).as(key + " : erreurs OAuth").contains("400", "401")
					.doesNotContain("403");
				assertThat(op.has(OpenApiCustomization.PERMISSIONS_EXTENSION)).as(key + " : x-permissions").isFalse();
			}
			else if (INTEGRATION_LINK.contains(key)) {
				assertThat(op.get("security").toString()).as(key + " : liaison").isEqualTo("[{\"integrationLink\":[]}]");
				assertThat(responses.propertyNames()).as(key + " : 401").contains("401");
				assertThat(op.has(OpenApiCustomization.PERMISSIONS_EXTENSION)).as(key + " : x-permissions").isFalse();
			}
			else if (!isPublic) {
				assertThat(responses.propertyNames()).as(key + " : 401 et 403").contains("401", "403");
				assertThat(op.has(OpenApiCustomization.PERMISSIONS_EXTENSION)).as(key + " : x-permissions").isTrue();
			}
			if (key.contains("{")) {
				assertThat(responses.propertyNames()).as(key + " : 404").contains("404");
			}
		});
		assertThat(ids).as("identifiants uniques").doesNotHaveDuplicates();
		assertThat(ids).as("pas d'identifiant numéroté par défaut (get_1…)").noneMatch(id -> id.matches(".*_\\d+$"));
		assertThat(this.handlers.keySet()).as("chaque route du code est documentée").containsExactlyInAnyOrderElementsOf(ids);
	}

	@Test
	void documentedPermissionsAreTheOnesTheCodeChecks() {
		this.operations.forEach((key, op) -> {
			if (!op.has(OpenApiCustomization.PERMISSIONS_EXTENSION)) {
				return;
			}
			List<String> documented = new ArrayList<>();
			op.get(OpenApiCustomization.PERMISSIONS_EXTENSION).forEach(p -> documented.add(p.asString()));
			assertThat(Permissions.ALL).as(key + " : permissions connues").containsAll(documented);
			HandlerMethod handler = this.handlers.get(op.get("operationId").asString());
			assertThat(documented).as(key).isEqualTo(OpenApiCustomization.permissions(handler));
			if (!documented.isEmpty()) {
				assertThat(op.get("description").asString()).as(key + " : permission rappelée")
					.contains(documented.get(0));
			}
		});
	}

	@Test
	void theWarehouseHeaderIsDocumentedWhereverItIsRead() {
		this.handlers.forEach((id, handler) -> {
			boolean reads = Arrays.stream(handler.getMethodParameters())
				.anyMatch(p -> p.hasParameterAnnotation(CurrentWarehouse.class));
			JsonNode op = operation(id);
			boolean documented = false;
			for (JsonNode parameter : op.path("parameters")) {
				documented |= OpenApiCustomization.WAREHOUSE_HEADER.equals(parameter.path("name").asString())
						&& "header".equals(parameter.path("in").asString());
			}
			assertThat(documented).as(id + " : en-tête X-Warehouse-Id").isEqualTo(reads);
		});
	}

	@Test
	void theAllWarehousesViewStatesItsPermissionEverywhere() {
		int found = 0;
		for (Map.Entry<String, JsonNode> path : this.spec.path("paths").properties()) {
			for (Map.Entry<String, JsonNode> op : path.getValue().properties()) {
				for (JsonNode parameter : op.getValue().path("parameters")) {
					if (OpenApiCustomization.ALL_WAREHOUSES_PARAMETER.equals(parameter.path("name").asString())) {
						found++;
						assertThat(parameter.path("description").asString())
							.as(op.getKey() + " " + path.getKey() + " : allWarehouses")
							.isEqualTo(OpenApiCustomization.ALL_WAREHOUSES_DESCRIPTION);
					}
				}
			}
		}
		assertThat(found).as("routes avec vue tous dépôts").isGreaterThan(20);
	}

	@Test
	void creationAndEmptyResponsesUseTheirRealStatus() throws IOException {
		// Source : une méthode qui renvoie ResponseEntity.created / noContent porte le @ResponseStatus
		// correspondant, qui fixe le code documenté (201 / 204).
		try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
			for (Path file : files.filter(f -> f.toString().endsWith("Controller.java")).toList()) {
				String source = Files.readString(file);
				for (String method : source.split("\n\t@(?=(Get|Post|Put|Patch|Delete)Mapping)")) {
					if (method.contains("ResponseEntity.created(")) {
						assertThat(method).as(file + " : 201 documenté").contains("@ResponseStatus(HttpStatus.CREATED)");
					}
					if (method.contains("ResponseEntity.noContent()")) {
						assertThat(method).as(file + " : 204 documenté").contains("@ResponseStatus(HttpStatus.NO_CONTENT)");
					}
				}
			}
		}
		assertThat(operation("createSale").get("responses").has("201")).isTrue();
		assertThat(operation("logout").get("responses").has("204")).isTrue();
	}

	// --- Erreurs ------------------------------------------------------------------------------------

	@Test
	void errorsAreDescribedWithTheirCatalogue() {
		JsonNode apiError = this.spec.get("components").get("schemas").get("ApiError");
		List<String> codes = new ArrayList<>();
		apiError.get("properties").get("code").get("enum").forEach(c -> codes.add(c.asString()));
		assertThat(codes).containsExactlyInAnyOrderElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
		for (String response : List.of("BadRequest", "Unauthorized", "Forbidden", "NotFound", "Conflict",
				"UnprocessableContent", "InternalError")) {
			JsonNode media = this.spec.get("components").get("responses").get(response).get("content")
				.get("application/json");
			assertThat(media.get("schema").get("$ref").asString()).isEqualTo("#/components/schemas/ApiError");
			assertThat(ErrorCode.valueOf(media.get("example").get("code").asString())).isNotNull();
		}
		String description = this.spec.get("info").get("description").asString();
		for (ErrorCode code : ErrorCode.values()) {
			assertThat(description).as("catalogue").contains("`" + code.name() + "`");
		}
	}

	// --- DTO et validation --------------------------------------------------------------------------

	@Test
	void validationRulesAreVisibleInTheSchemas() {
		int checked = 0;
		for (HandlerMethod handler : this.handlers.values()) {
			for (MethodParameter parameter : handler.getMethodParameters()) {
				if (!parameter.hasParameterAnnotation(RequestBody.class)) {
					continue;
				}
				JsonNode op = operation(OpenApiCustomization.operationId(handler));
				JsonNode schema = resolve(op.get("requestBody").get("content").get("application/json").get("schema"));
				checked += checkConstraints(parameter.getParameterType(), schema, handler.toString());
			}
		}
		assertThat(checked).as("contraintes vérifiées").isGreaterThan(150);
	}

	private int checkConstraints(Class<?> type, JsonNode schema, String where) {
		if (!type.isRecord()) {
			return 0;
		}
		Set<String> required = new HashSet<>();
		schema.path("required").forEach(r -> required.add(r.asString()));
		int checked = 0;
		for (RecordComponent component : type.getRecordComponents()) {
			Field field;
			try {
				field = type.getDeclaredField(component.getName());
			}
			catch (NoSuchFieldException ex) {
				continue;
			}
			String name = component.getName();
			JsonNode property = resolve(schema.path("properties").path(name));
			String label = where + " → " + type.getSimpleName() + "." + name;
			if (field.isAnnotationPresent(NotNull.class) || field.isAnnotationPresent(NotBlank.class)
					|| field.isAnnotationPresent(NotEmpty.class) || component.isAnnotationPresent(NotNull.class)
					|| component.isAnnotationPresent(NotBlank.class) || component.isAnnotationPresent(NotEmpty.class)) {
				assertThat(required).as(label + " : obligatoire").contains(name);
				checked++;
			}
			if (field.isAnnotationPresent(NotEmpty.class) && List.class.isAssignableFrom(component.getType())) {
				assertThat(property.path("minItems").asInt(0)).as(label + " : au moins un élément").isGreaterThanOrEqualTo(1);
				checked++;
			}
			if ((field.isAnnotationPresent(NotBlank.class) || field.isAnnotationPresent(NotEmpty.class))
					&& component.getType() == String.class) {
				assertThat(property.path("minLength").asInt(0)).as(label + " : texte non vide").isGreaterThanOrEqualTo(1);
				checked++;
			}
			Size size = field.getAnnotation(Size.class);
			if (size != null && size.max() < Integer.MAX_VALUE) {
				int max = property.has("maxLength") ? property.get("maxLength").asInt()
						: property.path("maxItems").asInt(-1);
				assertThat(max).as(label + " : taille max").isEqualTo(size.max());
				checked++;
			}
			Min min = field.getAnnotation(Min.class);
			if (min != null) {
				assertThat(property.get("minimum").asLong()).as(label + " : minimum").isEqualTo(min.value());
				checked++;
			}
			Max max = field.getAnnotation(Max.class);
			if (max != null) {
				assertThat(property.get("maximum").asLong()).as(label + " : maximum").isEqualTo(max.value());
				checked++;
			}
			// Records imbriqués : objet ou liste d'objets (lignes de vente, de commande…).
			if (component.getType().isRecord()) {
				checked += checkConstraints(component.getType(), property, where);
			}
			else if (List.class.isAssignableFrom(component.getType())
					&& component.getGenericType() instanceof java.lang.reflect.ParameterizedType list
					&& list.getActualTypeArguments()[0] instanceof Class<?> item && item.isRecord()) {
				checked += checkConstraints(item, resolve(property.path("items")), where);
			}
		}
		return checked;
	}

	// --- Exemples -----------------------------------------------------------------------------------

	@Test
	void examplesAreRealRequestsThatPassValidationAndMatchTheDtos() {
		Map<String, Map<String, Object>> examples = OpenApiCustomization.loadExamples(this.json);
		assertThat(examples).as("exemples présents").hasSizeGreaterThan(30);
		examples.forEach((operationId, example) -> {
			HandlerMethod handler = this.handlers.get(operationId);
			assertThat(handler).as("exemple " + operationId + " : opération existante").isNotNull();
			JsonNode op = operation(operationId);
			Object request = example.get("request");
			if (request != null) {
				MethodParameter body = Arrays.stream(handler.getMethodParameters())
					.filter(p -> p.hasParameterAnnotation(RequestBody.class))
					.findFirst()
					.orElseThrow(() -> new AssertionError(operationId + " : pas de corps de requête"));
				Object dto = this.json.convertValue(request, this.json.constructType(body.getGenericParameterType()));
				assertThat(this.validator.validate(dto)).as(operationId + " : exemple de requête valide").isEmpty();
				assertThat(op.get("requestBody").get("content").get("application/json").has("example"))
					.as(operationId + " : exemple publié").isTrue();
			}
			Object response = example.get("response");
			if (response != null) {
				this.json.convertValue(response, responseType(handler));
				assertThat(op.toString()).as(operationId + " : exemple de réponse publié").contains("\"example\"");
			}
		});
	}

	private JavaType responseType(HandlerMethod handler) {
		Type type = handler.getMethod().getGenericReturnType();
		JavaType javaType = this.json.constructType(type);
		return javaType.hasRawClass(ResponseEntity.class) ? javaType.containedType(0) : javaType;
	}

	// --- Outils -------------------------------------------------------------------------------------

	private JsonNode operation(String operationId) {
		return this.operations.values().stream()
			.filter(op -> operationId.equals(op.get("operationId").asString()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Opération non documentée : " + operationId));
	}

	private JsonNode resolve(JsonNode schema) {
		if (schema != null && schema.has("$ref")) {
			String name = schema.get("$ref").asString().substring("#/components/schemas/".length());
			return this.spec.get("components").get("schemas").get(name);
		}
		if (schema != null && schema.has("allOf")) {
			return resolve(schema.get("allOf").get(0));
		}
		return schema;
	}

}
