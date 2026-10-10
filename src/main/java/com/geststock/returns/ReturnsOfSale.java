package com.geststock.returns;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.sale.SaleReturnsChecker;

/** Indique au module ventes si une vente a des retours (elle ne peut alors plus être annulée). */
@Component
@Transactional(readOnly = true)
class ReturnsOfSale implements SaleReturnsChecker {

	private final CustomerReturnRepository returns;

	ReturnsOfSale(CustomerReturnRepository returns) {
		this.returns = returns;
	}

	@Override
	public boolean hasReturns(UUID saleId) {
		return this.returns.existsBySale(saleId);
	}

}
