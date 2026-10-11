package com.geststock.purchase;

import java.util.List;

import com.geststock.purchase.dto.GoodsReceiptResponse;
import com.geststock.purchase.dto.GoodsReceiptResponse.GoodsReceiptLineResponse;
import com.geststock.purchase.dto.PurchaseOrderResponse;
import com.geststock.purchase.dto.PurchaseOrderResponse.PurchaseOrderLineResponse;
import com.geststock.purchase.dto.SupplierInvoiceResponse;
import com.geststock.purchase.dto.SupplierPurchaseHistoryResponse;

/** Conversion des pièces d'achat en DTO ; les entités ne sortent jamais du module. */
final class PurchaseMapper {

	private PurchaseMapper() {
	}

	static PurchaseOrderResponse toResponse(PurchaseOrder order) {
		PurchaseOrderStatus status = order.getStatus();
		boolean nothingExpected = status == PurchaseOrderStatus.RECEIVED || status == PurchaseOrderStatus.CLOSED;
		return new PurchaseOrderResponse(order.getId(), order.getNumber(), order.getSupplierId(), order.getSupplierName(),
				order.getWarehouseId(), status.legacyStatus(), status, order.getExpectedDeliveryDate(),
				order.getSupplierReference(), order.getNote(),
				order.getLines().stream().map(PurchaseMapper::toResponse).toList(), order.getTotalAmount(),
				order.getReceivedAmount(), order.getPaidAmount(), order.remainingToPay(), order.getCreatedAt(), order.getCreatedBy(),
				nothingExpected ? order.getLastReceiptAt() : null, order.getLastReceiptAt(), order.getClosedAt(),
				order.getCloseReason(), order.getUpdatedAt(), invoiceOf(order));
	}

	/** Facture du fournisseur, ou {@code null} si elle n'est pas enregistrée. */
	static SupplierInvoiceResponse invoiceOf(PurchaseOrder order) {
		if (order.getInvoiceNumber() == null) {
			return null;
		}
		return new SupplierInvoiceResponse(order.getId(), order.getSupplierId(), order.getInvoiceNumber(),
				order.getInvoiceDate(), order.getInvoicedAmount(), order.getInvoicedAt(), order.getInvoicedBy());
	}

	static GoodsReceiptResponse toResponse(GoodsReceipt receipt, List<GoodsReceiptLine> lines) {
		return new GoodsReceiptResponse(receipt.getId(), receipt.getNumber(), receipt.getPurchaseOrder().getId(),
				receipt.getPurchaseOrder().getNumber(), receipt.getWarehouseId(), receipt.getReceivedAt(),
				receipt.getCreatedBy(), receipt.getSupplierDeliveryNote(), receipt.getNote(), receipt.getTotalQuantity(),
				receipt.getTotalAmount(), receipt.getCreatedAt(),
				lines.stream().map(PurchaseMapper::toResponse).toList());
	}

	static SupplierPurchaseHistoryResponse toHistory(GoodsReceipt receipt) {
		PurchaseOrder order = receipt.getPurchaseOrder();
		return new SupplierPurchaseHistoryResponse(receipt.getId(), order.getSupplierId(), receipt.getReceivedAt(),
				receipt.getNumber(), order.getId(), order.getNumber(), receipt.getTotalQuantity(),
				receipt.getTotalAmount());
	}

	private static PurchaseOrderLineResponse toResponse(PurchaseOrderLine line) {
		return new PurchaseOrderLineResponse(line.getId(), line.getLineNumber(), line.getProductId(),
				line.getProductSku(), line.getProductName(), line.getOrderedQuantity(), line.getReceivedQuantity(),
				line.remainingQuantity(), line.getUnitCost(), line.getLineTotal());
	}

	private static GoodsReceiptLineResponse toResponse(GoodsReceiptLine line) {
		PurchaseOrderLine orderLine = line.getOrderLine();
		return new GoodsReceiptLineResponse(line.getId(), line.getLineNumber(), line.getProductId(),
				orderLine.getProductSku(), orderLine.getProductName(), line.getQuantity(), line.getUnitCost(),
				line.getLineTotal(), line.getStockMovementId());
	}

}
