package com.geststock.purchase;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.Hibernate;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * Ligne reçue (table {@code goods_receipt_line}, V015), en ajout seul. Désigne le mouvement
 * de stock {@code RECEIPT} qu'elle a produit : la base refuse une ligne sans mouvement
 * exactement correspondant ({@code fn_goods_receipt_line_guard}).
 */
@Entity
@Immutable
@Table(name = "goods_receipt_line")
public class GoodsReceiptLine {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "goods_receipt_id", nullable = false)
	private GoodsReceipt receipt;

	@Column(name = "line_number", nullable = false)
	private int lineNumber;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "purchase_order_line_id", nullable = false)
	private PurchaseOrderLine orderLine;

	@Column(name = "product_id", nullable = false)
	private UUID productId;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Column(name = "unit_cost", nullable = false, precision = 14, scale = 2)
	private BigDecimal unitCost;

	@Column(name = "line_total", nullable = false, precision = 14, scale = 2)
	private BigDecimal lineTotal;

	@Column(name = "stock_movement_id", nullable = false)
	private UUID stockMovementId;

	protected GoodsReceiptLine() {
		// JPA
	}

	GoodsReceiptLine(GoodsReceipt receipt, int lineNumber, PurchaseOrderLine orderLine, int quantity,
			UUID stockMovementId) {
		this.receipt = Objects.requireNonNull(receipt, "receipt");
		this.lineNumber = lineNumber;
		this.orderLine = Objects.requireNonNull(orderLine, "orderLine");
		this.productId = orderLine.getProductId();
		this.quantity = quantity;
		this.unitCost = orderLine.getUnitCost();
		this.lineTotal = orderLine.amountOf(quantity);
		this.stockMovementId = Objects.requireNonNull(stockMovementId, "stockMovementId");
	}

	public UUID getId() {
		return this.id;
	}

	public GoodsReceipt getReceipt() {
		return this.receipt;
	}

	public int getLineNumber() {
		return this.lineNumber;
	}

	public PurchaseOrderLine getOrderLine() {
		return this.orderLine;
	}

	public UUID getProductId() {
		return this.productId;
	}

	public int getQuantity() {
		return this.quantity;
	}

	public BigDecimal getUnitCost() {
		return this.unitCost;
	}

	public BigDecimal getLineTotal() {
		return this.lineTotal;
	}

	public UUID getStockMovementId() {
		return this.stockMovementId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		GoodsReceiptLine line = (GoodsReceiptLine) other;
		return this.id != null && this.id.equals(line.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "GoodsReceiptLine[id=" + this.id + ", product=" + this.productId + ", quantity=" + this.quantity + "]";
	}

}
