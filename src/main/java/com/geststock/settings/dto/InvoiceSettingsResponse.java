package com.geststock.settings.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Configuration enregistrée et état du logo.
 *
 * @param logo      logo enregistré, ou {@code null} : l'image se lit sur {@code GET /api/settings/invoice/logo}
 * @param updatedAt dernière modification de la configuration
 */
@Schema(name = "InvoiceSettingsResponse")
public record InvoiceSettingsResponse(
		InvoiceSettings.Company company,
		InvoiceSettings.Header header,
		InvoiceSettings.Footer footer,
		InvoiceSettings.Appearance appearance,
		LogoInfo logo,
		Instant updatedAt) {

	/** Logo enregistré (métadonnées ; l'image est servie à part). */
	@Schema(name = "InvoiceLogoInfo")
	public record LogoInfo(String contentType, int sizeBytes, int width, int height,
			@Schema(description = "Empreinte SHA-256 : change à chaque nouveau logo") String sha256, Instant updatedAt) {
	}

}
