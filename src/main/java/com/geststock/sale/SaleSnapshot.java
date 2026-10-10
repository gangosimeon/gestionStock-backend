package com.geststock.sale;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.geststock.cash.PaymentMethod;

/**
 * Vue d'une vente pour les autres modules (retours) : ce qu'un retour doit connaître — lignes,
 * montants nets, coûts, règlement — sans exposer l'entité.
 */
public record SaleSnapshot(UUID id, String number, UUID warehouseId, UUID customerId, SaleStatus status,
		BigDecimal paidAmount, PaymentMethod paymentMethod, List<Line> lines) {

	/**
	 * @param lineTotal montant net de la ligne (remise déduite)
	 * @param unitCost  coût unitaire au moment de la vente
	 */
	public record Line(UUID id, UUID productId, String productSku, String productName, int quantity,
			BigDecimal lineTotal, BigDecimal unitCost) {
	}

	static SaleSnapshot of(Sale sale) {
		return new SaleSnapshot(sale.getId(), sale.getNumber(), sale.getWarehouseId(), sale.getCustomerId(),
				sale.getStatus(), sale.getPaidAmount(), sale.getPaymentMethod(),
				sale.getLines().stream()
					.map(line -> new Line(line.getId(), line.getProductId(), line.getProductSku(), line.getProductName(),
							line.getQuantity(), line.getLineTotal(), line.getUnitCost()))
					.toList());
	}

}
