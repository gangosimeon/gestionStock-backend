package com.geststock.sale.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAlias;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;
import com.geststock.customer.PriceCategory;

/**
 * Enregistrement d'une vente. Le client ne transmet <b>aucun prix ni coût</b> : uniquement
 * produits, quantités et remises demandées. Les champs {@code unitPrice}, {@code purchasePrice},
 * {@code total} ou {@code profit} envoyés par le front actuel sont ignorés.
 *
 * @param type          tarif : RETAIL ou WHOLESALE ; par défaut celui du client, sinon RETAIL
 * @param warehouseId   facultatif : s'il est envoyé, doit être le dépôt courant (X-Warehouse-Id),
 *                      sinon 409 {@code WAREHOUSE_MISMATCH} ; la vente a toujours lieu dans le dépôt courant
 * @param paidAmount    montant encaissé, de 0 au total ; total s'il est absent
 * @param paymentMethod obligatoire dès qu'un montant est payé
 * @param newCustomer   client absent de la base (nom, téléphone) : créé avec la vente, dans la
 *                      même transaction ; exclusif de {@code customerId}
 */
@Schema(name = "CreateSaleRequest")
public record CreateSaleRequest(
		PriceCategory type,
		UUID customerId,
		UUID warehouseId,
		PaymentMethod paymentMethod,
		@DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal paidAmount,
		@Size(max = 500) String note,
		@NotEmpty(message = "le panier est vide") @Size(max = 200) @JsonAlias("lines") List<@Valid @NotNull Item> items,
		@Valid NewCustomer newCustomer,
		@DecimalMin("0") @Digits(integer = 12, fraction = 2)
		@Schema(description = "Espèces remises par le client (paiement en espèces) : la monnaie rendue est calculée et "
				+ "conservée par le serveur ; au moins égal au montant payé", example = "10000") BigDecimal cashReceived) {

	/** Vente sans montant reçu saisi (signature antérieure). */
	public CreateSaleRequest(PriceCategory type, UUID customerId, UUID warehouseId, PaymentMethod paymentMethod,
			BigDecimal paidAmount, String note, List<Item> items, NewCustomer newCustomer) {
		this(type, customerId, warehouseId, paymentMethod, paidAmount, note, items, newCustomer, null);
	}

	/** Vente à un client existant ou au comptoir (signature d'origine). */
	public CreateSaleRequest(PriceCategory type, UUID customerId, UUID warehouseId, PaymentMethod paymentMethod,
			BigDecimal paidAmount, String note, List<Item> items) {
		this(type, customerId, warehouseId, paymentMethod, paidAmount, note, items, null, null);
	}

	@AssertTrue(message = "choisir un client existant ou en saisir un nouveau, pas les deux")
	@Schema(hidden = true)
	public boolean isSingleCustomerChoice() {
		return this.customerId == null || this.newCustomer == null;
	}

	/**
	 * Client saisi à la caisse. Réutilisé s'il existe déjà un client actif de même nom et de
	 * même téléphone ; sinon créé (comptant, sans plafond de crédit).
	 */
	@Schema(name = "SaleNewCustomer")
	public record NewCustomer(
			@NotBlank @Size(max = 200) @Schema(example = "Koffi Yao") String name,
			@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$", message = "numéro de téléphone invalide")
			@Schema(example = "+225 07 01 02 03") String phone) {
	}

	/**
	 * Article vendu.
	 *
	 * @param discountPercent remise demandée en % (0 à 100), plafonnée par la configuration
	 */
	@Schema(name = "CreateSaleItem")
	public record Item(
			@NotNull UUID productId,
			@NotNull @Min(1) @Max(100_000) Integer quantity,
			@DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal discountPercent) {
	}

}
