package com.geststock.settings.dto;

import java.util.Locale;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.settings.InvoiceTemplate;
import com.geststock.settings.LogoPosition;
import com.geststock.settings.TextAlignment;
import com.geststock.settings.ThousandsSeparator;

/**
 * Configuration des factures de vente : entreprise, en-tête, pied de page, apparence. Un texte
 * vide signifie « non configuré » : il n'est jamais affiché sur une facture réelle. Les textes
 * sont débarrassés de leurs espaces de début et de fin ; un texte absent ({@code null}) vaut vide.
 */
@Schema(name = "InvoiceSettings")
public record InvoiceSettings(
		@NotNull @Valid Company company,
		@NotNull @Valid Header header,
		@NotNull @Valid Footer footer,
		@NotNull @Valid Appearance appearance) {

	static final String PHONE_PATTERN = "^[0-9+() .-]*$";

	static final String COLOR_PATTERN = "^#[0-9A-Fa-f]{6}$";

	/** Valeurs d'une installation neuve (identiques aux valeurs par défaut de V035). */
	public static InvoiceSettings defaults() {
		return new InvoiceSettings(
				new Company("", "", "", "", "", "", "", "", "", "", ""),
				new Header(true, true, true, true, true, true, true, true, true, true, false, LogoPosition.LEFT,
						TextAlignment.RIGHT, true, ""),
				new Footer("Merci pour votre confiance !", true, "", true, "", true, "", true, "", true, "", true, "",
						true, "", true, "", true, TextAlignment.CENTER, true),
				new Appearance(InvoiceTemplate.MODERN, "#1E3A8A", "#F59E0B", "#1F2937", "dd/MM/yyyy", "FCFA",
						ThousandsSeparator.SPACE, 0, true, true, true, true, true, "", true));
	}

	static String text(String value) {
		return Objects.requireNonNullElse(value, "").trim();
	}

	/** Coordonnées de l'entreprise. Aucune n'est obligatoire : chaque entreprise renseigne les siennes. */
	@Schema(name = "InvoiceCompany")
	public record Company(
			@Size(max = 150) @Schema(example = "Quincaillerie Moderne") String companyName,
			@Size(max = 150) @Schema(description = "Raison sociale, si différente du nom commercial") String legalName,
			@Size(max = 200) @Schema(example = "Qualité, service et confiance") String slogan,
			@Size(max = 255) @Schema(example = "Avenue de l'Indépendance") String address,
			@Size(max = 100) @Schema(example = "Ouagadougou") String city,
			@Size(max = 100) @Schema(example = "Burkina Faso") String country,
			@Size(max = 50) @Pattern(regexp = PHONE_PATTERN, message = "numéro de téléphone invalide")
			@Schema(example = "+226 70 12 34 56") String phone,
			@Size(max = 254) @Email(regexp = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
			@Schema(example = "contact@quincaillerie.bf") String email,
			@Size(max = 200) @Pattern(regexp = "^$|^(https?://)?[^\\s/]+\\.[^\\s]+$", message = "adresse de site invalide")
			@Schema(example = "www.quincaillerie.bf") String website,
			@Size(max = 100) @Schema(description = "Numéro d'enregistrement (RCCM)", example = "BF OUA 2023 B 4567")
			String registrationNumber,
			@Size(max = 100) @Schema(description = "Identifiant fiscal (IFU / NIF)", example = "00012345X") String taxId) {

		public Company {
			companyName = text(companyName);
			legalName = text(legalName);
			slogan = text(slogan);
			address = text(address);
			city = text(city);
			country = text(country);
			phone = text(phone);
			email = text(email);
			website = text(website);
			registrationNumber = text(registrationNumber);
			taxId = text(taxId);
		}

	}

	/** Éléments affichés en haut de la facture et leur disposition. */
	@Schema(name = "InvoiceHeader")
	public record Header(
			boolean showLogo,
			boolean showCompanyName,
			boolean showSlogan,
			boolean showAddress,
			boolean showCityCountry,
			boolean showPhone,
			boolean showEmail,
			boolean showWebsite,
			boolean showRegistrationNumber,
			boolean showTaxId,
			@Schema(description = "Afficher le dépôt où la vente a eu lieu") boolean showWarehouse,
			@NotNull LogoPosition logoPosition,
			@NotNull @Schema(description = "Alignement des coordonnées") TextAlignment headerAlignment,
			boolean headerSeparator,
			@Size(max = 300) @Schema(description = "Texte complémentaire sous l'en-tête") String headerNote) {

		public Header {
			headerNote = text(headerNote);
		}

	}

	/** Mentions de bas de facture ; chacune a son interrupteur d'affichage. */
	@Schema(name = "InvoiceFooter")
	public record Footer(
			@Size(max = 300) String thankYouMessage, boolean showThankYou,
			@Size(max = 1000) String salesTerms, boolean showSalesTerms,
			@Size(max = 1000) String returnPolicy, boolean showReturnPolicy,
			@Size(max = 500) String paymentTerms, boolean showPaymentTerms,
			@Size(max = 500) @Schema(description = "Coordonnées de paiement (Mobile Money, banque…)") String paymentDetails,
			boolean showPaymentDetails,
			@Size(max = 300) String promoMessage, boolean showPromoMessage,
			@Size(max = 1000) String legalText, boolean showLegalText,
			@Size(max = 200) String signatureText, boolean showSignature,
			@Size(max = 300) String closingText, boolean showClosingText,
			@NotNull TextAlignment footerAlignment,
			boolean footerSeparator) {

		public Footer {
			thankYouMessage = text(thankYouMessage);
			salesTerms = text(salesTerms);
			returnPolicy = text(returnPolicy);
			paymentTerms = text(paymentTerms);
			paymentDetails = text(paymentDetails);
			promoMessage = text(promoMessage);
			legalText = text(legalText);
			signatureText = text(signatureText);
			closingText = text(closingText);
		}

	}

	/** Modèle, couleurs, formats et colonnes facultatives. */
	@Schema(name = "InvoiceAppearance")
	public record Appearance(
			@NotNull InvoiceTemplate template,
			@NotNull @Pattern(regexp = COLOR_PATTERN, message = "couleur attendue au format #RRGGBB")
			@Schema(example = "#1E3A8A") String primaryColor,
			@NotNull @Pattern(regexp = COLOR_PATTERN, message = "couleur attendue au format #RRGGBB")
			@Schema(example = "#F59E0B") String secondaryColor,
			@NotNull @Pattern(regexp = COLOR_PATTERN, message = "couleur attendue au format #RRGGBB")
			@Schema(example = "#1F2937") String textColor,
			@NotNull @Pattern(regexp = "^(dd/MM/yyyy|dd-MM-yyyy|yyyy-MM-dd|d MMMM yyyy)$", message = "format de date non pris en charge")
			@Schema(example = "dd/MM/yyyy") String dateFormat,
			@NotNull @Pattern(regexp = "^(FCFA|F CFA|XOF)$", message = "devise non prise en charge")
			@Schema(example = "FCFA") String currencyLabel,
			@NotNull ThousandsSeparator thousandsSeparator,
			@NotNull @Schema(description = "0 ou 2", example = "0") Integer decimals,
			boolean showLineNumber,
			boolean showSku,
			boolean showDiscountColumn,
			boolean showAmountInWords,
			boolean showPageNumbers,
			@Size(max = 200) @Schema(description = "Mention en bas de chaque page du PDF") String pageFooterText,
			boolean showPageFooterText) {

		public Appearance {
			primaryColor = primaryColor == null ? null : primaryColor.trim().toUpperCase(Locale.ROOT);
			secondaryColor = secondaryColor == null ? null : secondaryColor.trim().toUpperCase(Locale.ROOT);
			textColor = textColor == null ? null : textColor.trim().toUpperCase(Locale.ROOT);
			pageFooterText = text(pageFooterText);
		}

		@AssertTrue(message = "nombre de décimales : 0 ou 2")
		@JsonIgnore
		@Schema(hidden = true)
		public boolean isDecimalsSupported() {
			return this.decimals == null || this.decimals == 0 || this.decimals == 2;
		}

	}

}
