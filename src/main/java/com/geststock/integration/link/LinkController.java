package com.geststock.integration.link;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.CacheControl;
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
 * Routes de la liaison appelante (lot 2, étapes G11 et G14 ; contrat {@code GET /link}, {@code POST /link/revoke},
 * {@code POST /link/secret}, C2, v14-D6, v14-D10) : toute portée suffit. {@code GET /link} et {@code POST /link/revoke}
 * restent servies pendant une suspension ; {@code POST /link/secret} non ({@code 403 LINK_SUSPENDED}). {@code GET /link}
 * lit les dépôts dans la base de l'entreprise (contrôles d'entreprise et de migration de la chaîne) ;
 * {@code POST /link/revoke} ne touche que la base plateforme ({@code platformOnly} : aucune entreprise courante).
 */
@RestController
@RequestMapping(IntegrationSecurityConfig.BASE_PATH + "/link")
@Tag(name = "Intégration JeCréeMaBoutique", description = "Routes du client d'intégration (contrat integration-v1)")
class LinkController {

	private static final Logger log = LoggerFactory.getLogger(LinkController.class);

	private static final String API_ERROR = "#/components/schemas/ApiError";

	private final LinkRegistry links;

	private final LinkViews views;

	private final LinkRevocations revocations;

	private final RateLimiter limiter;

	private final IntegrationRateLimitProperties rateLimits;

	private final ApiErrorFactory errors;

	LinkController(LinkRegistry links, LinkViews views, LinkRevocations revocations, RateLimiter limiter,
			IntegrationRateLimitProperties rateLimits, ApiErrorFactory errors) {
		this.links = links;
		this.views = views;
		this.revocations = revocations;
		this.limiter = limiter;
		this.rateLimits = rateLimits;
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

	@PostMapping("/secret")
	@RequiresScope(RequiresScope.ANY)
	@Operation(operationId = "rotateLinkSecret", summary = "Rotation du secret de liaison, de serveur à serveur (v14-D6)",
			description = "Liaison ACTIVE ; SUSPENDED : 403 LINK_SUSPENDED (v14-D10). Émet un nouveau secret, renvoyé une "
					+ "seule fois (Cache-Control: no-store) ; GestStock n'en conserve que l'empreinte SHA-256. L'ancien "
					+ "secret reste accepté par client_credentials jusqu'au premier usage du nouveau, 24 h au plus. Pas "
					+ "d'Idempotency-Key : réponse perdue ⇒ garder l'ancien secret, encore valide, et rappeler la route ; "
					+ "le nouvel appel remplace le secret en attente. Limite : 5 rotations par heure et par liaison "
					+ "(configurable), 429 RATE_LIMITED avec Retry-After. Chaque rotation est inscrite au journal "
					+ "d'audit. Un secret compromis ne se traite pas par une rotation : révoquer la liaison "
					+ "(POST /link/revoke) puis en créer une nouvelle.",
			security = @SecurityRequirement(name = OpenApiConfig.INTEGRATION_LINK_SCHEME),
			responses = { @ApiResponse(responseCode = "200", description = "Nouveau secret de liaison",
					headers = @Header(name = HttpHeaders.CACHE_CONTROL, description = "no-store",
							schema = @Schema(type = "string", allowableValues = "no-store"))),
					@ApiResponse(responseCode = "401", description = "INVALID_TOKEN, LINK_REVOKED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "403", description = "LINK_SUSPENDED, COMPANY_SUSPENDED, COMPANY_UNAVAILABLE",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "429", description = "RATE_LIMITED : plus de 5 rotations par heure pour la "
							+ "liaison (valeur de départ, configurable) ; en-tête Retry-After",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))),
					@ApiResponse(responseCode = "503",
							description = "RATE_LIMIT_UNAVAILABLE, SCHEMA_UPGRADE_IN_PROGRESS, SCHEMA_UPGRADE_FAILED",
							content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
									schema = @Schema(ref = API_ERROR))) })
	ResponseEntity<LinkSecretResponse> rotateSecret(@Parameter(hidden = true) @AuthenticationPrincipal LinkPrincipal principal) {
		// Après l'authentification et l'autorisation de la chaîne : une liaison suspendue (403) ne consomme pas son quota.
		IntegrationRateLimitProperties.RateLimit limits = this.rateLimits.rateLimit();
		RateLimiter.Decision decision = this.limiter.hit("link-secret:link:" + principal.linkId(), limits.linkSecretPerLink(),
				limits.linkSecretWindow());
		if (!decision.allowed()) {
			log.info("Limitation de débit : {} ({} / {}) ⇒ 429, Retry-After {} s.", decision.key(), decision.hits(),
					decision.limit(), decision.retryAfterSeconds());
			throw new RotationRateLimited(decision.retryAfterSeconds());
		}
		RotatedSecret rotated;
		try {
			rotated = this.links.rotate(principal.linkId());
		}
		catch (LinkException ex) {
			// Suspendue ou révoquée entre le contrôle de la chaîne et la rotation : même réponse que la chaîne.
			Link link = this.links.find(principal.linkId()).orElseThrow(LinkAlreadyRevoked::new);
			if (link.status() == LinkStatus.SUSPENDED) {
				throw new LinkSuspendedMeanwhile();
			}
			throw new LinkAlreadyRevoked();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header(HttpHeaders.PRAGMA, "no-cache")
			.body(new LinkSecretResponse(rotated.secret(), rotated.rotatedAt(), rotated.previousSecretValidUntil()));
	}

	@ExceptionHandler(RotationRateLimited.class)
	ResponseEntity<ApiError> rateLimited(RotationRateLimited ex, HttpServletRequest request) {
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
		log.warn("Limitation de débit indisponible ⇒ 503, rotation refusée : {}.", ex.getCause().getClass().getSimpleName());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
			.header(HttpHeaders.PRAGMA, "no-cache")
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.RATE_LIMIT_UNAVAILABLE, null, request));
	}

	/** Suspendue entre le contrôle de la chaîne et la rotation : comme la chaîne (v14-D10). */
	@ExceptionHandler(LinkSuspendedMeanwhile.class)
	ResponseEntity<ApiError> suspended(HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(this.errors.create(ErrorCode.LINK_SUSPENDED, null, request));
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
	 * Réponse {@code 200} de {@code POST /link/secret} (contrat {@code LinkSecretResponse}) : le secret n'est jamais
	 * journalisé (Spring MVC journalise en DEBUG le corps écrit, via {@code toString}).
	 *
	 * @param linkSecret               nouveau secret, renvoyé une seule fois
	 * @param rotatedAt                émission du nouveau secret
	 * @param previousSecretValidUntil limite de validité de l'ancien secret (plus tôt dès le premier usage du nouveau)
	 */
	@Schema(name = "IntegrationLinkSecretResponse")
	record LinkSecretResponse(
			@JsonProperty("link_secret") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 32,
					maxLength = 256) String linkSecret,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant rotatedAt,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant previousSecretValidUntil) {

		@Override
		public String toString() {
			return "LinkSecretResponse[***]";
		}

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

	/** Quota de rotations de la liaison épuisé. */
	static final class RotationRateLimited extends RuntimeException {

		private final long retryAfterSeconds;

		RotationRateLimited(long retryAfterSeconds) {
			super("Rotations trop fréquentes", null, false, false);
			this.retryAfterSeconds = retryAfterSeconds;
		}

	}

	/** Liaison suspendue entre le contrôle de la chaîne et la rotation. */
	static final class LinkSuspendedMeanwhile extends RuntimeException {

		LinkSuspendedMeanwhile() {
			super("Liaison suspendue", null, false, false);
		}

	}

	/** Liaison déjà révoquée. */
	static final class LinkAlreadyRevoked extends RuntimeException {

		LinkAlreadyRevoked() {
			super("Liaison déjà révoquée", null, false, false);
		}

	}

}
