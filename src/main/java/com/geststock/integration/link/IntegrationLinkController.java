package com.geststock.integration.link;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.security.CurrentUser;
import com.geststock.tenancy.TenantContext;

/**
 * Consentement et gestion des liaisons avec les boutiques en ligne (lot 2, étape G10 ; v14-D1 étapes 2 et 3), sur la
 * chaîne principale : personnes connectées de l'entreprise, permission {@code integration:manage}. Ce ne sont pas des
 * routes du contrat d'intégration. L'entreprise est toujours celle de la personne connectée (jeton), jamais un
 * paramètre de la requête.
 *
 * <p>Le {@code request_uri} voyage dans le corps JSON d'un {@code POST} : jamais dans l'URL (journaux d'accès du proxy
 * et de l'hébergeur, en-tête {@code Referer}), et les corps sont masqués dans les journaux de l'application
 * ({@code toString}).
 */
@RestController
@RequestMapping("/api/integration-links")
@Tag(name = "Intégration — liaisons boutique")
class IntegrationLinkController {

	private final LinkConsentService consent;

	IntegrationLinkController(LinkConsentService consent) {
		this.consent = consent;
	}

	@PostMapping("/authorization-requests/lookup")
	@RequiresPermission(Permissions.INTEGRATION_MANAGE)
	@Operation(operationId = "getIntegrationAuthorizationRequest", summary = "Consulter une demande de liaison",
			description = "Demande déposée par la boutique (POST /oauth/par), retrouvée par son request_uri : boutique, "
					+ "application, portées demandées, expiration et devise de l'entreprise. Refus explicites : "
					+ "AUTHORIZATION_REQUEST_NOT_FOUND, AUTHORIZATION_REQUEST_EXPIRED, "
					+ "AUTHORIZATION_REQUEST_ALREADY_DECIDED, LINK_CURRENCY_UNSUPPORTED.")
	LinkConsentService.RequestView lookup(@Valid @RequestBody AuthorizationRequestReference request) {
		return this.consent.lookup(request.requestUri(), currentCompany());
	}

	@PostMapping("/authorization-requests/approve")
	@RequiresPermission(Permissions.INTEGRATION_MANAGE)
	@Operation(operationId = "approveIntegrationAuthorizationRequest", summary = "Approuver une demande de liaison",
			description = "Crée la liaison en attente de l'entreprise, au nom de la personne connectée, et renvoie l'URL "
					+ "de retour vers la boutique (redirect_uri enregistrée, code, state, iss). Portées accordées incluses "
					+ "dans celles demandées.")
	AuthorizationRedirect approve(@AuthenticationPrincipal CurrentUser user,
			@Valid @RequestBody AuthorizationApproval request) {
		return new AuthorizationRedirect(this.consent.approve(request.requestUri(), Set.copyOf(request.scopes()),
				currentCompany(), authorizer(user)));
	}

	@PostMapping("/authorization-requests/deny")
	@RequiresPermission(Permissions.INTEGRATION_MANAGE)
	@Operation(operationId = "denyIntegrationAuthorizationRequest", summary = "Refuser une demande de liaison",
			description = "Aucune liaison ; URL de retour vers la boutique avec error=access_denied, state et iss.")
	AuthorizationRedirect deny(@Valid @RequestBody AuthorizationRequestReference request) {
		return new AuthorizationRedirect(this.consent.deny(request.requestUri()));
	}

	@GetMapping
	@RequiresPermission(Permissions.INTEGRATION_MANAGE)
	@Operation(operationId = "listIntegrationLinks", summary = "Lister les liaisons de l'entreprise")
	List<IntegrationLinkView> list() {
		return this.consent.list(currentCompany()).stream().map(IntegrationLinkView::of).toList();
	}

	@PostMapping("/{id}/suspend")
	@RequiresPermission(Permissions.INTEGRATION_MANAGE)
	@Operation(operationId = "suspendIntegrationLink", summary = "Suspendre une liaison",
			description = "Liaison active seulement (motif MANUAL) ; la boutique reçoit 403 LINK_SUSPENDED.")
	IntegrationLinkView suspend(@PathVariable UUID id) {
		return IntegrationLinkView.of(this.consent.suspend(id, currentCompany()));
	}

	@PostMapping("/{id}/reactivate")
	@RequiresPermission(Permissions.INTEGRATION_MANAGE)
	@Operation(operationId = "reactivateIntegrationLink", summary = "Réactiver une liaison suspendue",
			description = "La personne connectée devient responsable de la liaison.")
	IntegrationLinkView reactivate(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
		return IntegrationLinkView.of(this.consent.reactivate(id, currentCompany(), authorizer(user)));
	}

	@PostMapping("/{id}/revoke")
	@RequiresPermission(Permissions.INTEGRATION_MANAGE)
	@Operation(operationId = "revokeIntegrationLink", summary = "Révoquer une liaison",
			description = "Définitif (motif GESTSTOCK_ADMIN) ; la boutique reçoit 401 LINK_REVOKED.")
	IntegrationLinkView revoke(@PathVariable UUID id) {
		return IntegrationLinkView.of(this.consent.revoke(id, currentCompany()));
	}

	private static UUID currentCompany() {
		return TenantContext.require().companyId();
	}

	private static Authorizer authorizer(CurrentUser user) {
		return new Authorizer(user.id(), user.username());
	}

	/** Demande désignée par son {@code request_uri} (masqué dans les journaux). */
	record AuthorizationRequestReference(@NotBlank @Size(max = 200) String requestUri) {

		@Override
		public String toString() {
			return "AuthorizationRequestReference[***]";
		}

	}

	/** Approbation : {@code request_uri} (masqué dans les journaux) et portées accordées. */
	record AuthorizationApproval(@NotBlank @Size(max = 200) String requestUri,
			@NotEmpty @Size(max = 6) List<@NotBlank @Size(max = 30) String> scopes) {

		@Override
		public String toString() {
			return "AuthorizationApproval[***, scopes=" + this.scopes + "]";
		}

	}

	/** URL de retour vers la boutique, à suivre par le navigateur ; porte le code : masquée dans les journaux. */
	record AuthorizationRedirect(String redirectUrl) {

		@Override
		public String toString() {
			return "AuthorizationRedirect[***]";
		}

	}

	/**
	 * Liaison de l'entreprise, sans aucune empreinte.
	 *
	 * @param authorizedBy nom d'utilisateur de la personne responsable de la liaison
	 */
	@Schema(name = "IntegrationLink")
	record IntegrationLinkView(UUID id, String shopId, String shopName, LinkStatus status,
			SuspensionReason suspensionReason, RevocationReason revocationReason, String currency, List<String> scopes,
			String authorizedBy, Instant createdAt, Instant activatedAt, Instant suspendedAt, Instant revokedAt) {

		static IntegrationLinkView of(Link link) {
			return new IntegrationLinkView(link.id(), link.externalShopId(), link.shopName(), link.status(),
					link.suspensionReason(), link.revocationReason(), link.currency(), List.copyOf(new TreeSet<>(link.scopes())),
					link.authorizedByUsername(), link.createdAt(), link.activatedAt(), link.suspendedAt(), link.revokedAt());
		}

	}

}
