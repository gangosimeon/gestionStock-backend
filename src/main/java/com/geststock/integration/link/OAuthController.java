package com.geststock.integration.link;

import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.config.OpenApiConfig;

/**
 * Routes du client JeCréeMaBoutique (lot 2, étape G8 ; contrat {@code /oauth/par} et {@code /oauth/token}) : corps
 * {@code application/x-www-form-urlencoded} seulement, aucun paramètre dans l'URL, chaque paramètre au plus une fois
 * (RFC 6749 § 3.2) ; réponses et erreurs au format OAuth, avec {@code Cache-Control: no-store} et
 * {@code Pragma: no-cache}. Le client est authentifié en HTTP Basic par la chaîne d'intégration.
 */
@RestController
@RequestMapping(IntegrationSecurityConfig.BASE_PATH + "/oauth")
@Tag(name = "Intégration JeCréeMaBoutique", description = "Routes du client d'intégration (contrat integration-v1)")
class OAuthController {

	private static final String FORM = MediaType.APPLICATION_FORM_URLENCODED_VALUE;

	private static final String OAUTH_ERROR = "#/components/schemas/" + OpenApiConfig.OAUTH_ERROR_SCHEMA;

	private static final String TOO_MANY_REQUESTS = "#/components/responses/" + OpenApiConfig.TOO_MANY_REQUESTS_RESPONSE;

	private static final String RATE_LIMIT_UNAVAILABLE = "#/components/responses/"
			+ OpenApiConfig.RATE_LIMIT_UNAVAILABLE_RESPONSE;

	private final OAuthService oauth;

	OAuthController(OAuthService oauth) {
		this.oauth = oauth;
	}

	@PostMapping("/par")
	@Operation(operationId = "pushIntegrationAuthorizationRequest",
			summary = "Déposer une demande d'autorisation de liaison (PAR, RFC 9126)",
			description = "Client authentifié en HTTP Basic. Le request_uri renvoyé est valable 10 minutes et ne sert "
					+ "qu'une fois ; il est présenté à l'écran d'autorisation de GestStock.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_CLIENT_SCHEME),
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
					content = @Content(mediaType = FORM, schemaProperties = {
							@SchemaProperty(name = "response_type", schema = @Schema(allowableValues = "code")),
							@SchemaProperty(name = "redirect_uri", schema = @Schema(type = "string")),
							@SchemaProperty(name = "scope", schema = @Schema(type = "string")),
							@SchemaProperty(name = "state", schema = @Schema(type = "string", minLength = 32, maxLength = 512)),
							@SchemaProperty(name = "code_challenge", schema = @Schema(type = "string")),
							@SchemaProperty(name = "code_challenge_method", schema = @Schema(allowableValues = "S256")),
							@SchemaProperty(name = "shop_id", schema = @Schema(type = "string", maxLength = 64)),
							@SchemaProperty(name = "shop_name", schema = @Schema(type = "string", maxLength = 200)) })),
			responses = {
					@ApiResponse(responseCode = "201", description = "Demande enregistrée",
							content = @Content(mediaType = "application/json", schemaProperties = {
									@SchemaProperty(name = "request_uri", schema = @Schema(type = "string")),
									@SchemaProperty(name = "expires_in", schema = @Schema(type = "integer")) })),
					@ApiResponse(responseCode = "400", description = "invalid_request ou invalid_scope",
							content = @Content(mediaType = "application/json", schema = @Schema(ref = OAUTH_ERROR))),
					@ApiResponse(responseCode = "401", description = "invalid_client (en-tête WWW-Authenticate: Basic)",
							content = @Content(mediaType = "application/json", schema = @Schema(ref = OAUTH_ERROR))),
					@ApiResponse(responseCode = "429", ref = TOO_MANY_REQUESTS),
					@ApiResponse(responseCode = "503", ref = RATE_LIMIT_UNAVAILABLE) })
	ResponseEntity<OAuthResponseBody> pushAuthorizationRequest(
			@Parameter(hidden = true) @AuthenticationPrincipal IntegrationClients.AuthenticatedClient client,
			HttpServletRequest request) {
		return respond(HttpStatus.CREATED, this.oauth.push(client, form(request)));
	}

	@PostMapping("/token")
	@Operation(operationId = "issueIntegrationToken",
			summary = "Obtenir un jeton de liaison (authorization_code ou client_credentials)",
			description = "Client authentifié en HTTP Basic. authorization_code (code, redirect_uri, code_verifier) "
					+ "active la liaison et renvoie link et link_secret une seule fois ; un code rejoué révoque la "
					+ "liaison. client_credentials (link_id, link_secret, scope facultatif) renouvelle le jeton.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_CLIENT_SCHEME),
			requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
					content = @Content(mediaType = FORM, schemaProperties = {
							@SchemaProperty(name = "grant_type",
									schema = @Schema(allowableValues = { "authorization_code", "client_credentials" })),
							@SchemaProperty(name = "code", schema = @Schema(type = "string")),
							@SchemaProperty(name = "redirect_uri", schema = @Schema(type = "string")),
							@SchemaProperty(name = "code_verifier", schema = @Schema(type = "string")),
							@SchemaProperty(name = "link_id", schema = @Schema(type = "string", format = "uuid")),
							@SchemaProperty(name = "link_secret", schema = @Schema(type = "string")),
							@SchemaProperty(name = "scope", schema = @Schema(type = "string")) })),
			responses = {
					@ApiResponse(responseCode = "200", description = "Jeton émis (15 minutes)",
							content = @Content(mediaType = "application/json", schemaProperties = {
									@SchemaProperty(name = "access_token", schema = @Schema(type = "string")),
									@SchemaProperty(name = "token_type", schema = @Schema(allowableValues = "Bearer")),
									@SchemaProperty(name = "expires_in", schema = @Schema(type = "integer")),
									@SchemaProperty(name = "scope", schema = @Schema(type = "string")),
									@SchemaProperty(name = "link", schema = @Schema(type = "object",
											description = "authorization_code seulement")),
									@SchemaProperty(name = "link_secret", schema = @Schema(type = "string",
											description = "authorization_code seulement, renvoyé une fois")) })),
					@ApiResponse(responseCode = "400",
							description = "invalid_request, invalid_grant (code LINK_REVOKED si la liaison est révoquée), "
									+ "invalid_scope ou unsupported_grant_type",
							content = @Content(mediaType = "application/json", schema = @Schema(ref = OAUTH_ERROR))),
					@ApiResponse(responseCode = "401", description = "invalid_client (en-tête WWW-Authenticate: Basic)",
							content = @Content(mediaType = "application/json", schema = @Schema(ref = OAUTH_ERROR))),
					@ApiResponse(responseCode = "429", ref = TOO_MANY_REQUESTS),
					@ApiResponse(responseCode = "503", ref = RATE_LIMIT_UNAVAILABLE) })
	ResponseEntity<OAuthResponseBody> token(
			@Parameter(hidden = true) @AuthenticationPrincipal IntegrationClients.AuthenticatedClient client,
			HttpServletRequest request) {
		Map<String, String> form = form(request);
		String grantType = form.get("grant_type");
		if (grantType == null) {
			throw OAuthException.invalidRequest("Paramètre requis : grant_type.");
		}
		return switch (grantType) {
			case "authorization_code" -> respond(HttpStatus.OK, this.oauth.exchangeCode(client, form));
			case "client_credentials" -> respond(HttpStatus.OK, this.oauth.clientCredentials(client, form));
			default -> throw OAuthException.unsupportedGrantType();
		};
	}

	@ExceptionHandler(OAuthException.class)
	ResponseEntity<OAuthResponseBody> oauthError(OAuthException ex) {
		return respond(ex.status(), ex.body());
	}

	/**
	 * Corps de formulaire seulement, sans paramètre d'URL ni paramètre répété. Lu ici plutôt que reçu en argument :
	 * Spring MVC journalise en TRACE les arguments résolus d'une méthode, et donc {@code link_secret} ou
	 * {@code code_verifier}.
	 */
	private static Map<String, String> form(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null || !MediaType.APPLICATION_FORM_URLENCODED.includes(MediaType.parseMediaType(contentType))) {
			throw OAuthException.invalidRequest("Corps application/x-www-form-urlencoded attendu.");
		}
		if (request.getQueryString() != null) {
			throw OAuthException.invalidRequest("Aucun paramètre n'est accepté dans l'URL.");
		}
		Map<String, String> form = new LinkedHashMap<>();
		for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
			if (entry.getValue().length != 1) {
				throw OAuthException.invalidRequest("Paramètre répété : " + entry.getKey() + ".");
			}
			form.put(entry.getKey(), entry.getValue()[0]);
		}
		return form;
	}

	/** Corps masqué dans les journaux ({@link OAuthResponseBody}), jamais mis en cache. */
	private static ResponseEntity<OAuthResponseBody> respond(HttpStatus status, Map<String, Object> body) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
			.contentType(MediaType.APPLICATION_JSON).body(new OAuthResponseBody(body));
	}

}
