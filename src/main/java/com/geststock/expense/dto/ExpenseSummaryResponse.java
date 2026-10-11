package com.geststock.expense.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.expense.ExpenseCategory;

/**
 * Totaux des dépenses payées sur les critères demandés (les annulées n'y comptent pas).
 *
 * @param total          somme des dépenses payées
 * @param count          nombre de dépenses payées
 * @param cashTotal      part payée en espèces (sortie des caisses)
 * @param cancelledCount dépenses annulées sur les mêmes critères
 * @param byCategory     répartition par catégorie, du montant le plus élevé au plus faible
 */
@Schema(name = "ExpenseSummary")
public record ExpenseSummaryResponse(BigDecimal total, long count, BigDecimal cashTotal, long cancelledCount,
		List<CategoryTotal> byCategory) {

	/** Dépenses payées d'une catégorie. */
	@Schema(name = "ExpenseCategoryTotal")
	public record CategoryTotal(ExpenseCategory category, long count, BigDecimal total) {
	}

}
