package com.geststock.expense.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.expense.ExpenseCategory;
import com.geststock.expense.ExpensePaymentMethod;
import com.geststock.expense.ExpenseStatus;

/**
 * Dépense (contrat du front {@code Expense}).
 *
 * @param number             référence DEP-AAAA-nnnnnn
 * @param expenseDateIso     date de la dépense
 * @param status             PAID (comptée) ou CANCELLED (hors totaux)
 * @param cancellationReason motif, pour une dépense annulée
 */
@Schema(name = "Expense")
public record ExpenseResponse(
		UUID id,
		String number,
		UUID warehouseId,
		ExpenseCategory category,
		String label,
		BigDecimal amount,
		LocalDate expenseDateIso,
		ExpensePaymentMethod paymentMethod,
		String externalReference,
		String note,
		ExpenseStatus status,
		Instant cancelledAt,
		UUID cancelledByUserId,
		String cancellationReason,
		Instant createdAt,
		UUID createdByUserId) {
}
