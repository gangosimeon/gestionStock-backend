package com.geststock.platform.company;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.exception.ApiError;
import com.geststock.platform.company.dto.CompanyDtos.ActivationLinkResponse;
import com.geststock.platform.company.dto.CompanyDtos.AuditEntry;
import com.geststock.platform.company.dto.CompanyDtos.CompanyListItem;
import com.geststock.platform.company.dto.CompanyDtos.CompanyResponse;
import com.geststock.platform.company.dto.CompanyDtos.CreateCompanyRequest;
import com.geststock.platform.company.dto.CompanyDtos.PlatformStats;
import com.geststock.platform.company.dto.CompanyDtos.SuspendCompanyRequest;
import com.geststock.platform.company.dto.CompanyDtos.UpdateCompanyRequest;

/** Administration des entreprises : réservée au Super Admin (jeton plateforme). */
@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Plateforme — entreprises", description = "Création, provisionnement, suspension des entreprises (Super Admin)")
@ApiResponse(responseCode = "403", description = "Réservé au Super Admin",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
class CompanyController {

	private final CompanyService service;

	CompanyController(CompanyService service) {
		this.service = service;
	}

	@GetMapping("/stats")
	@Operation(summary = "Tableau de bord de la plateforme")
	@ApiResponse(responseCode = "200", description = "OK")
	PlatformStats stats() {
		return this.service.stats();
	}

	@GetMapping("/companies")
	@Operation(summary = "Lister les entreprises", description = "Recherche (code, nom, ville, propriétaire), statut, tri : tradeName | code | number | createdAt | status | subscriptionEndsOn.")
	@ApiResponse(responseCode = "200", description = "OK")
	ResponseEntity<PageResponse<CompanyListItem>> list(@RequestParam(required = false) String q,
			@Parameter(description = "PENDING | PROVISIONING | ACTIVE | FAILED | SUSPENDED") @RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.service.list(q, status, query), query);
	}

	@PostMapping("/companies")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Créer une entreprise",
			description = "Enregistre l'entreprise puis lance le provisionnement (base dédiée, rôle, migrations, administrateur). "
					+ "En-tête Idempotency-Key conseillé : une même clé renvoie l'entreprise déjà créée (200).")
	@ApiResponse(responseCode = "201", description = "Entreprise créée, provisionnement en cours")
	@ApiResponse(responseCode = "409", description = "COMPANY_CODE_ALREADY_USED",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<CompanyResponse> create(@Valid @RequestBody CreateCompanyRequest request,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		CompanyService.Created result = this.service.create(request, idempotencyKey);
		if (!result.created()) {
			return ResponseEntity.ok(result.company());
		}
		return ResponseEntity.created(URI.create("/api/platform/companies/" + result.company().id())).body(result.company());
	}

	@GetMapping("/companies/{id}")
	@Operation(summary = "Fiche d'une entreprise", description = "Informations, base (nom seulement), provisionnement et étapes.")
	@ApiResponse(responseCode = "200", description = "OK")
	CompanyResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PutMapping("/companies/{id}")
	@Operation(summary = "Modifier une entreprise", description = "Le code public et l'identifiant de l'administrateur ne changent pas.")
	@ApiResponse(responseCode = "200", description = "OK")
	CompanyResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCompanyRequest request) {
		return this.service.update(id, request);
	}

	@PostMapping("/companies/{id}/suspend")
	@Operation(summary = "Suspendre une entreprise", description = "Accès coupé, sessions révoquées. Aucune donnée supprimée.")
	@ApiResponse(responseCode = "200", description = "OK")
	CompanyResponse suspend(@PathVariable UUID id, @Valid @RequestBody SuspendCompanyRequest request) {
		return this.service.suspend(id, request.reason());
	}

	@PostMapping("/companies/{id}/reactivate")
	@Operation(summary = "Réactiver une entreprise suspendue")
	@ApiResponse(responseCode = "200", description = "OK")
	CompanyResponse reactivate(@PathVariable UUID id) {
		return this.service.reactivate(id);
	}

	@PostMapping("/companies/{id}/provisioning/retry")
	@Operation(summary = "Relancer le provisionnement", description = "Reprise à l'étape échouée ; aucune ressource n'est recréée.")
	@ApiResponse(responseCode = "200", description = "OK")
	CompanyResponse retryProvisioning(@PathVariable UUID id) {
		return this.service.retryProvisioning(id);
	}

	@PostMapping("/companies/{id}/admin-access-link")
	@Operation(summary = "Lien d'activation de l'administrateur",
			description = "Lien à usage unique (7 jours) à transmettre au propriétaire. Montré une seule fois ; les liens précédents sont invalidés.")
	@ApiResponse(responseCode = "200", description = "OK")
	ActivationLinkResponse adminAccessLink(@PathVariable UUID id) {
		return this.service.adminAccessLink(id);
	}

	@GetMapping("/audit-log")
	@Operation(summary = "Journal d'administration de la plateforme")
	@ApiResponse(responseCode = "200", description = "OK")
	ResponseEntity<PageResponse<AuditEntry>> auditLog(@RequestParam(required = false) UUID companyId,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.service.auditLog(companyId, query), query);
	}

}
