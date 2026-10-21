package com.geststock.platform.branding;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Contrats de la personnalisation des entreprises. */
public final class BrandingDtos {

	static final String COLOR = "^#[0-9A-Fa-f]{6}$";

	static final String COLOR_MESSAGE = "couleur hexadécimale #RRGGBB attendue";

	private BrandingDtos() {
	}

	/**
	 * Couleurs (validées {@code #RRGGBB}, aucun CSS libre), mode par défaut et mentions.
	 */
	@Schema(name = "BrandingRequest")
	public record BrandingRequest(
			@NotBlank @Pattern(regexp = COLOR, message = COLOR_MESSAGE) @Schema(example = "#2563EB") String primaryColor,
			@NotBlank @Pattern(regexp = COLOR, message = COLOR_MESSAGE) @Schema(example = "#7C3AED") String secondaryColor,
			@NotBlank @Pattern(regexp = COLOR, message = COLOR_MESSAGE) @Schema(example = "#16A34A") String accentColor,
			@NotBlank @Pattern(regexp = COLOR, message = COLOR_MESSAGE) @Schema(example = "#0B1F3A") String sidebarColor,
			@NotBlank @Pattern(regexp = COLOR, message = COLOR_MESSAGE) @Schema(example = "#FFFFFF") String headerColor,
			@NotBlank @Pattern(regexp = COLOR, message = COLOR_MESSAGE) @Schema(example = "#2563EB") String buttonColor,
			@NotBlank @Pattern(regexp = COLOR, message = COLOR_MESSAGE) @Schema(example = "#2563EB") String linkColor,
			@NotBlank @Pattern(regexp = "^(LIGHT|DARK)$") @Schema(example = "LIGHT") String themeMode,
			@NotNull Boolean highContrast,
			@Size(max = 300) String footerText) {
	}

	/** Réglage réservé au Super Admin : l'entreprise peut-elle modifier elle-même sa personnalisation ? */
	@Schema(name = "BrandingPermissionRequest")
	public record BrandingPermissionRequest(@NotNull Boolean companyCanEdit) {
	}

	@Schema(name = "Branding")
	public record BrandingResponse(String companyCode, String companyName, String primaryColor, String secondaryColor,
			String accentColor, String sidebarColor, String headerColor, String buttonColor, String linkColor,
			String themeMode, boolean highContrast, String footerText, boolean companyCanEdit,
			@Schema(description = "URL publique du logo (absente sans logo) ; change à chaque nouveau logo") String logoUrl,
			Instant updatedAt) {
	}

	@Schema(name = "PublicBranding", description = "Marque affichée sur l'écran de connexion (aucune donnée métier)")
	public record PublicBrandingResponse(String code, String name, String primaryColor, String secondaryColor,
			String accentColor, String sidebarColor, String headerColor, String buttonColor, String linkColor,
			String themeMode, boolean highContrast, String logoUrl,
			@Schema(description = "ACTIVE ou SUSPENDED") String status) {
	}

	/** Profil de l'entreprise vu par son administrateur. */
	@Schema(name = "CompanyProfile")
	public record CompanyProfileResponse(String code, String legalName, String tradeName, String sector, String address,
			String city, String country, String phone, String email, String website, String taxId, String currency,
			String plan, java.time.LocalDate subscriptionEndsOn, BrandingResponse branding) {
	}

	/** Coordonnées modifiables par l'administrateur de l'entreprise (raison sociale et formule restent gérées par GestStock). */
	@Schema(name = "UpdateCompanyProfileRequest")
	public record UpdateCompanyProfileRequest(@NotBlank @Size(max = 150) String tradeName, @Size(max = 255) String address,
			@Size(max = 100) String city, @Size(max = 100) String country,
			@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$") String phone,
			@jakarta.validation.constraints.Email @Size(max = 254) String email, @Size(max = 200) String website) {
	}

}
