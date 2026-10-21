package com.geststock.platform.branding;

import java.time.Duration;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.platform.branding.BrandingDtos.PublicBrandingResponse;

/**
 * Marque publique d'une entreprise, pour l'écran de connexion : nom, couleurs, logo. Aucune donnée métier
 * ni information de compte. Ne prouve rien : l'accès reste décidé par l'authentification.
 */
@RestController
@RequestMapping("/api/public/companies/{code}")
@Tag(name = "Public — marque des entreprises", description = "Affichage de l'écran de connexion")
class PublicBrandingController {

	private final BrandingService service;

	PublicBrandingController(BrandingService service) {
		this.service = service;
	}

	@GetMapping("/branding")
	@SecurityRequirements
	@Operation(summary = "Marque d'une entreprise", description = "Entreprises actives ou suspendues ; 404 sinon.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "COMPANY_NOT_FOUND")
	ResponseEntity<PublicBrandingResponse> branding(@PathVariable String code) {
		PublicBrandingResponse branding = this.service.publicBranding(code).orElseThrow(PublicBrandingController::notFound);
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic()).body(branding);
	}

	@GetMapping("/logo")
	@SecurityRequirements
	@Operation(summary = "Logo d'une entreprise", description = "PNG ou JPEG vérifié au téléversement.")
	@ApiResponse(responseCode = "200", description = "OK")
	@ApiResponse(responseCode = "404", description = "COMPANY_NOT_FOUND")
	ResponseEntity<byte[]> logo(@PathVariable String code) {
		BrandingService.Logo logo = this.service.publicLogo(code).orElseThrow(PublicBrandingController::notFound);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(logo.contentType()))
			.cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
			.header("X-Content-Type-Options", "nosniff")
			.header("Content-Security-Policy", "default-src 'none'")
			.body(logo.data());
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.COMPANY_NOT_FOUND, ErrorCode.COMPANY_NOT_FOUND.defaultMessage());
	}

}
