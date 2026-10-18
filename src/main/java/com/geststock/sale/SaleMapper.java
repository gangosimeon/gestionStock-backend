package com.geststock.sale;

import com.geststock.sale.dto.SaleResponse;
import com.geststock.sale.dto.SaleResponse.SaleItemResponse;

/** Conversion des ventes en DTO ; les entités ne sortent jamais du module. */
final class SaleMapper {

	private SaleMapper() {
	}

	static SaleResponse toResponse(Sale sale) {
		return new SaleResponse(sale.getId(), sale.getNumber(), sale.getPriceType(), sale.getStatus(),
				sale.getWarehouseId(), sale.getCustomerId(), sale.getCustomerName(),
				sale.getLines().stream().map(SaleMapper::toResponse).toList(), sale.getPaymentMethod(),
				sale.getSubtotal(), sale.getDiscountTotal(), sale.getTotal(), sale.getPaidAmount(),
				sale.getCreditAmount(), sale.getCostTotal(), sale.getProfit(), sale.getNote(), sale.getCreatedAt(),
				sale.getCreatedBy(), sale.getCancelledAt(), sale.getCancellationReason(), sale.getCashReceived(),
				sale.getChangeGiven());
	}

	private static SaleItemResponse toResponse(SaleLine line) {
		return new SaleItemResponse(line.getId(), line.getLineNumber(), line.getProductId(), line.getProductSku(),
				line.getProductName(), line.getQuantity(), line.getUnitPrice(), line.getDiscountPercent(),
				line.getDiscountAmount(), line.getLineTotal(), line.getUnitCost(), line.getStockMovementId());
	}

}
