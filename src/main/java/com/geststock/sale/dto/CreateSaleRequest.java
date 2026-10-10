package com.geststock.sale.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
 * @param warehouseId   dépôt de vente ; dépôt courant (X-Warehouse-Id) s'il est absent
 * @param paidAmount    montant encaissé, de 0 au total ; total s'il est absent
 * @param paymentMethod obligatoire dès qu'un montant est payé
 */
@Schema(name = "CreateSaleRequest")
public record CreateSaleRequest(
		PriceCategory type,
		UUID customerId,
		UUID warehouseId,
		PaymentMethod paymentMethod,
		@DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal paidAmount,
		@Size(max = 500) String note,
		@NotEmpty(message = "le panier est vide") @Size(max = 200) @JsonAlias("lines") List<@Valid @NotNull Item> items) {

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
