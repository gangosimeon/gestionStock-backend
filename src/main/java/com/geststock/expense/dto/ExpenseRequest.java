package com.geststock.expense.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.geststock.expense.ExpenseCategory;
import com.geststock.expense.ExpensePaymentMethod;

/**
 * Nouvelle dépense, rattachée au dépôt courant. Contrat du front :
 * {@code {category, label, amount, expenseDateIso, paymentMethod, externalReference?, note?}}.
 *
 * @param expenseDate       date de la dépense ({@code expenseDateIso} accepté) ; aujourd'hui si
 *                          absente, jamais dans le futur
 * @param paymentMethod     espèces par défaut : une dépense en espèces sort de ma caisse ouverte
 * @param externalReference référence du virement, du chèque, de la transaction mobile ou du reçu
 */
@Schema(name = "ExpenseRequest")
public record ExpenseRequest(
		@NotNull @Schema(example = "TRANSPORT") ExpenseCategory category,
		@NotBlank @Size(max = 200) @Schema(example = "Livraison des montures") String label,
		@NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) @Schema(example = "15000") BigDecimal amount,
		@JsonAlias("expenseDateIso") @Schema(example = "2026-10-11") LocalDate expenseDate,
		@Schema(example = "CASH") ExpensePaymentMethod paymentMethod,
		@Size(max = 50) @Schema(example = "REC-0042") String externalReference,
		@Size(max = 500) String note) {
}
