package com.geststock.quote.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Création ou modification d'un devis. Seuls le client et les lignes sont obligatoires ; un
 * champ facultatif vide (ou fait d'espaces) est enregistré comme absent et n'apparaît sur aucun
 * document. Le total n'est pas transmis : le serveur le calcule à partir des lignes.
 *
 * @param quoteDate date du document ; aujourd'hui si absente (une date passée est admise)
 */
@Schema(name = "QuoteRequest")
public record QuoteRequest(
		@NotNull(message = "choisir un client") UUID customerId,
		@Schema(example = "2022-03-23") LocalDate quoteDate,
		LocalDate validUntil,
		@Size(max = 500) @Schema(example = "Fourniture des matériels de latrine au profit des PDI de la commune de Pissila")
		String subject,
		@Size(max = 100) @Schema(example = "2") String lotReference,
		@Min(1) @Max(1_000_000) @Schema(example = "20") Integer beneficiaries,
		@Size(max = 500) String deliveryAddress,
		@Size(max = 150) String contractReference,
		@Size(max = 1000) String deliveryTerms,
		@Size(max = 1000) String paymentTerms,
		@Size(max = 200) String executionDelay,
		@Size(max = 2000) String notes,
		@NotEmpty(message = "ajouter au moins un produit") @Size(max = 200) List<@Valid @NotNull Line> lines) {

	@AssertTrue(message = "la date de validité ne peut pas précéder la date du devis")
	@JsonIgnore
	@Schema(hidden = true)
	public boolean isValidityAfterDate() {
		return this.validUntil == null || this.quoteDate == null || !this.validUntil.isBefore(this.quoteDate);
	}

	/**
	 * Ligne du devis : produit existant du catalogue, quantité, prix unitaire retenu pour ce devis.
	 *
	 * @param quantity               quantité totale, saisie directement ; ignorée si
	 *                               {@code quantityPerBeneficiary} est renseignée
	 * @param quantityPerBeneficiary quantité pour un bénéficiaire : la quantité totale est alors
	 *                               calculée par le serveur (× nombre de bénéficiaires du devis)
	 */
	@Schema(name = "QuoteLineRequest")
	public record Line(
			@NotNull UUID productId,
			@Min(1) @Max(100_000) @Schema(example = "120") Integer quantity,
			@Min(1) @Max(100_000) @Schema(example = "6") Integer quantityPerBeneficiary,
			@NotNull @DecimalMin(value = "0.01", message = "le prix unitaire doit être positif")
			@DecimalMax("999999999.99") @Digits(integer = 9, fraction = 2) @Schema(example = "6000")
			BigDecimal unitPrice) {

		@AssertTrue(message = "indiquer la quantité, ou la quantité par bénéficiaire")
		@JsonIgnore
		@Schema(hidden = true)
		public boolean isQuantityGiven() {
			return this.quantity != null || this.quantityPerBeneficiary != null;
		}

	}

}
