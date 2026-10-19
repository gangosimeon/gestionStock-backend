package com.geststock.stock;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;

/**
 * Ligne d'un transfert (table {@code stock_transfer_line}, V044) : produit du catalogue,
 * référence et libellé recopiés, quantité transférée. Jamais modifiée.
 */
@Entity
@Table(name = "stock_transfer_line")
class StockTransferLine {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "transfer_id", nullable = false, updatable = false)
	private StockTransfer transfer;

	@Column(name = "line_number", nullable = false, updatable = false)
	private int lineNumber;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Column(name = "product_sku", nullable = false, updatable = false, length = 50)
	private String productSku;

	@Column(name = "product_name", nullable = false, updatable = false, length = 200)
	private String productName;

	@Column(name = "quantity", nullable = false, updatable = false)
	private int quantity;

	protected StockTransferLine() {
	}

	StockTransferLine(StockTransfer transfer, int lineNumber, UUID productId, String productSku, String productName,
			int quantity) {
		this.transfer = transfer;
		this.lineNumber = lineNumber;
		this.productId = productId;
		this.productSku = productSku;
		this.productName = productName;
		this.quantity = quantity;
	}

	UUID getId() {
		return this.id;
	}

	int getLineNumber() {
		return this.lineNumber;
	}

	UUID getProductId() {
		return this.productId;
	}

	String getProductSku() {
		return this.productSku;
	}

	String getProductName() {
		return this.productName;
	}

	int getQuantity() {
		return this.quantity;
	}

}
