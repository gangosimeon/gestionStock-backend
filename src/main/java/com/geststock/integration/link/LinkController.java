package com.geststock.integration.link;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.config.OpenApiConfig;
import com.geststock.exception.ApiError;
import com.geststock.exception.ApiErrorFactory;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.tenancy.TenantContext;

/**
 * Routes de la liaison appelante (lot 2, étape G11 ; contrat {@code GET /link}, {@code POST /link/revoke}, C2,
 * v14-D10) : toute portée suffit, et elles restent servies pendant une suspension. {@code GET /link} lit les dépôts
 * dans la base de l'entreprise (contrôles d'entreprise et de migration de la chaîne) ; {@code POST /link/revoke} ne
 * touche que la base plateforme ({@code platformOnly} : aucune entreprise courante).
 */
@RestController
@RequestMapping(IntegrationSecurityConfig.BASE_PATH + "/link")
@Tag(name = "Intégration JeCréeMaBoutique", description = "Routes du client d'intégration (contrat integration-v1)")
class LinkController {

	private static final String API_ERROR = "#/components/schemas/ApiError";

	private final LinkRegistry links;

	private final LinkViews views;

	private final LinkRevocations revocations;

	private final ApiErrorFactory errors;

	LinkController(LinkRegistry links, LinkViews views, LinkRevocations revocations, ApiErrorFactory errors) {
		this.links = links;
		this.views = views;
		this.revocations = revocations;
		this.errors = errors;
	}

	@GetMapping
	@RequiresScope(value = RequiresScope.ANY, allowedWhileSuspended = true)
	@Operation(operationId = "getLink", summary = "Statut et périmètre de la liaison appelante",
			description = "Accessible aussi pour une liaison SUSPENDED (v14-D10), afin d'en lire le motif. Portées "
					+ "effectives ; dépôts vendables de la liaison.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			responses = { @ApiResponse(responseCode = "200", description = "Liaison"),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403", description = "COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503", description = "SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	LinkView get(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal) {
		Link link = this.links.find(principal.linkId()).orElseThrow(LinkAlreadyRevoked::new);
		return this.views.withWarehouses(link, principal.scopes(), TenantContext.require());
	}

	@PostMapping("/revoke")
	@RequiresScope(value = RequiresScope.ANY, allowedWhileSuspended = true, platformOnly = true)
	@Operation(operationId = "revokeLink", summary = "Révocation définitive de la liaison par la boutique (C2)",
			description = "Liaison ACTIVE ou SUSPENDED : REVOKED (SHOP_REQUEST), secret invalidé. Motif facultatif (500 "
					+ "caractères au plus), réservé au journal d'audit. Déjà révoquée : 401 LINK_REVOKED, à traiter comme "
					+ "un succès. Réponse : la liaison, sans dépôt (une liaison révoquée n'en vend aucun).",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			responses = { @ApiResponse(responseCode = "200", description = "Liaison révoquée"),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	LinkView revoke(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal,
			@RequestBody(required = false) LinkRevocationRequest request) {
		String reason = request != null ? request.reason() : null;
		// Contrôle à la main plutôt que @Valid : l'exception de validation de Spring porte la valeur refusée et
		// serait journalisée (DEBUG, TRACE) ; le motif ne doit apparaître que dans l'audit.
		if (reason != null && reason.length() > LinkRevocationRequest.REASON_MAX) {
			throw new InvalidRequestException(ErrorCode.VALIDATION_FAILED,
					"Motif de révocation trop long : " + LinkRevocationRequest.REASON_MAX + " caractères au plus.");
		}
		Link revoked = this.revocations.byShop(principal.linkId(), reason);
		LinkAuthentication authentication = (LinkAuthentication) SecurityContextHolder.getContext().getAuthentication();
		return this.views.withoutWarehouses(revoked, principal.scopes(), authentication.company());
	}

	/** Déjà révoquée (révocation simultanée, ou entre le contrôle du jeton et la révocation) : comme la chaîne (G7). */
	@ExceptionHandler(LinkAlreadyRevoked.class)
	ResponseEntity<ApiError> alreadyRevoked(HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.header(HttpHeaders.WWW_AUTHENTICATE, IntegrationSecurityConfig.INVALID_TOKEN_CHALLENGE)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.LINK_REVOKED, null, request));
	}

	/**
	 * Corps facultatif de {@code POST /link/revoke}. Le motif est destiné au journal d'audit (G12) : masqué dans les
	 * journaux applicatifs.
	 */
	record LinkRevocationRequest(@Size(max = LinkRevocationRequest.REASON_MAX) String reason) {

		static final int REASON_MAX = 500;

		@Override
		public String toString() {
			return "LinkRevocationRequest[***]";
		}

	}

	/** Liaison déjà révoquée. */
	static final class LinkAlreadyRevoked extends RuntimeException {

		LinkAlreadyRevoked() {
			super("Liaison déjà révoquée", null, false, false);
		}

	}

}
