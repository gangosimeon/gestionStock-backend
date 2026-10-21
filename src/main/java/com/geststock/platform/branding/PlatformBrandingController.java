package com.geststock.platform.branding;

import java.io.IOException;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.geststock.exception.ApiError;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.platform.branding.BrandingDtos.BrandingPermissionRequest;
import com.geststock.platform.branding.BrandingDtos.BrandingRequest;
import com.geststock.platform.branding.BrandingDtos.BrandingResponse;

/** Personnalisation d'une entreprise par le Super Admin. */
@RestController
@RequestMapping("/api/platform/companies/{id}/branding")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@Tag(name = "Plateforme — personnalisation", description = "Couleurs, logo et mentions de chaque entreprise")
@ApiResponse(responseCode = "403", description = "Réservé au Super Admin",
		content = @Content(schema = @Schema(implementation = ApiError.class)))
class PlatformBrandingController {

	private final BrandingService service;

	PlatformBrandingController(BrandingService service) {
		this.service = service;
	}

	@GetMapping
	@Operation(summary = "Personnalisation d'une entreprise")
	@ApiResponse(responseCode = "200", description = "OK")
	BrandingResponse get(@PathVariable UUID id) {
		return this.service.get(id);
	}

	@PutMapping
	@Operation(summary = "Modifier couleurs, mode et mentions", description = "Couleurs #RRGGBB uniquement ; aucun CSS libre.")
	@ApiResponse(responseCode = "200", description = "OK")
	BrandingResponse update(@PathVariable UUID id, @Valid @RequestBody BrandingRequest request) {
		return this.service.update(id, request);
	}

	@PutMapping("/permission")
	@Operation(summary = "Autoriser l'entreprise à modifier sa personnalisation")
	@ApiResponse(responseCode = "200", description = "OK")
	BrandingResponse permission(@PathVariable UUID id, @Valid @RequestBody BrandingPermissionRequest request) {
		return this.service.setCompanyCanEdit(id, request.companyCanEdit());
	}

	@PostMapping("/logo")
	@Operation(summary = "Ajouter ou remplacer le logo",
			description = "Champ multipart « file » : PNG ou JPEG, 1 Mo et 2000 × 2000 pixels au plus, type vérifié sur le contenu.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "400", description = "BRANDING_INVALID_LOGO ou INVALID_LOGO",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	BrandingResponse uploadLogo(@PathVariable UUID id, @RequestPart(name = "file", required = false) MultipartFile file) {
		return this.service.uploadLogo(id, bytes(file));
	}

	@DeleteMapping("/logo")
	@Operation(summary = "Retirer le logo")
	@ApiResponse(responseCode = "200", description = "OK")
	BrandingResponse deleteLogo(@PathVariable UUID id) {
		return this.service.deleteLogo(id);
	}

	static byte[] bytes(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new InvalidRequestException(ErrorCode.BRANDING_INVALID_LOGO, "Fichier requis : choisissez une image PNG ou JPEG.");
		}
		try {
			return file.getBytes();
		}
		catch (IOException ex) {
			throw new InvalidRequestException(ErrorCode.BRANDING_INVALID_LOGO, "Fichier illisible : réessayez l'envoi.");
		}
	}

}
