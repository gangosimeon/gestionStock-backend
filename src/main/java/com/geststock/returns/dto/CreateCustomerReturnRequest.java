package com.geststock.returns.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.cash.PaymentMethod;
import com.geststock.returns.ItemCondition;
import com.geststock.returns.ReturnSettlement;

/**
 * Retour client sur une vente. Aucun montant n'est saisi : il est calculé au prix net de la
 * vente (remise comprise).
 *
 * @param refundMethod moyen de remboursement (REFUND) ; celui de la vente par défaut
 * @param reason       motif du retour (obligatoire, tracé)
 */
@Schema(name = "CreateCustomerReturnRequest")
public record CreateCustomerReturnRequest(
		@NotNull ReturnSettlement settlement,
		PaymentMethod refundMethod,
		@NotBlank @Size(max = 500) @Schema(example = "Monture défectueuse, charnière cassée") String reason,
		@NotEmpty @Size(max = 200) List<@Valid @NotNull Item> items) {

	/** Article retourné. */
	@Schema(name = "CustomerReturnItem")
	public record Item(
			@NotNull UUID productId,
			@NotNull @Min(1) @Max(100_000) Integer quantity,
			@NotNull ItemCondition condition) {
	}

}
