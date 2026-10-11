package com.geststock.expense.dto;

import java.time.LocalDate;

import com.geststock.expense.ExpenseCategory;
import com.geststock.expense.ExpensePaymentMethod;
import com.geststock.expense.ExpenseStatus;

/**
 * Critères des dépenses (liste et totaux).
 *
 * @param dateFrom      dépenses datées de ce jour ou après
 * @param dateTo        dépenses datées de ce jour ou avant
 * @param status        PAID ou CANCELLED (ignoré par les totaux, qui ne comptent que PAID)
 * @param search        texte cherché dans le libellé, la référence, la référence externe et la note
 * @param allWarehouses tous les dépôts (exige {@code warehouse:all}) au lieu du dépôt courant
 */
public record ExpenseFilter(LocalDate dateFrom, LocalDate dateTo, ExpenseCategory category,
		ExpensePaymentMethod paymentMethod, ExpenseStatus status, String search, Boolean allWarehouses) {
}
