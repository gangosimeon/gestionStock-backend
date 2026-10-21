package com.geststock.platform.branding;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ApiError;
import com.geststock.platform.branding.BrandingDtos.BrandingRequest;
import com.geststock.platform.branding.BrandingDtos.BrandingResponse;
import com.geststock.platform.branding.BrandingDtos.CompanyProfileResponse;
import com.geststock.platform.branding.BrandingDtos.UpdateCompanyProfileRequest;
import com.geststock.tenancy.CurrentTenant;

/**
 * Profil et personnalisation de <b>son</b> entreprise : l'entreprise est celle du jeton (claim signé),
 * jamais un identifiant fourni par le client. Lecture pour tout utilisateur connecté (thème de
 * l'interface) ; modification avec {@code company:manage}, et pour la personnalisation seulement si
 * GestStock l'autorise.
 */
@RestController
@RequestMapping("/api/company")
@Tag(name = "Entreprise — profil", description = "Coordonnées et identité visuelle de l'entreprise connectée")
@ApiResponse(responseCode = "403", description = "Permission manquante ou personnalisation non autorisée (BRANDING_NOT_EDITABLE)",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
class CompanyProfileController {

	private final BrandingService service;

	private final CurrentTenant currentTenant;

	CompanyProfileController(BrandingService service, CurrentTenant currentTenant) {
		this.service = service;
		this.currentTenant = currentTenant;
	}

	@GetMapping("/branding")
	@PreAuthorize("isAuthenticated()")
	@Operation(summary = "Thème de l'entreprise connectée")
	@ApiResponse(responseCode = "200", description = "OK")
	BrandingResponse branding() {
		return this.service.get(this.currentTenant.require().companyId());
	}

	@GetMapping("/profile")
	@RequiresPermission(Permissions.COMPANY_MANAGE)
	@Operation(summary = "Profil de l'entreprise connectée")
	@ApiResponse(responseCode = "200", description = "OK")
	CompanyProfileResponse profile() {
		return this.service.profile(this.currentTenant.require().companyId());
	}

	@PutMapping("/profile")
	@RequiresPermission(Permissions.COMPANY_MANAGE)
	@Operation(summary = "Modifier les coordonnées", description = "Raison sociale, identifiant fiscal et formule restent gérés par GestStock.")
	@ApiResponse(responseCode = "200", description = "OK")
	CompanyProfileResponse updateProfile(@Valid @RequestBody UpdateCompanyProfileRequest request) {
		return this.service.updateProfile(this.currentTenant.require().companyId(), request);
	}

	@PutMapping("/branding")
	@RequiresPermission(Permissions.COMPANY_MANAGE)
	@Operation(summary = "Modifier la personnalisation", description = "Possible seulement si GestStock l'a autorisé pour l'entreprise.")
	@ApiResponse(responseCode = "200", description = "OK")
	BrandingResponse updateBranding(@Valid @RequestBody BrandingRequest request) {
		var companyId = this.currentTenant.require().companyId();
		this.service.requireCompanyCanEdit(companyId);
		return this.service.update(companyId, request);
	}

	@PostMapping("/branding/logo")
	@RequiresPermission(Permissions.COMPANY_MANAGE)
	@Operation(summary = "Remplacer le logo", description = "Champ multipart « file » : PNG ou JPEG, 1 Mo au plus. Si GestStock l'autorise.")
	@ApiResponse(responseCode = "200", description = "OK")
	BrandingResponse uploadLogo(@RequestPart(name = "file", required = false) MultipartFile file) {
		var companyId = this.currentTenant.require().companyId();
		this.service.requireCompanyCanEdit(companyId);
		return this.service.uploadLogo(companyId, PlatformBrandingController.bytes(file));
	}

	@DeleteMapping("/branding/logo")
	@RequiresPermission(Permissions.COMPANY_MANAGE)
	@Operation(summary = "Retirer le logo", description = "Si GestStock l'autorise.")
	@ApiResponse(responseCode = "200", description = "OK")
	BrandingResponse deleteLogo() {
		var companyId = this.currentTenant.require().companyId();
		this.service.requireCompanyCanEdit(companyId);
		return this.service.deleteLogo(companyId);
	}

}
