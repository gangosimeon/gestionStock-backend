package com.geststock.purchase;

import java.math.BigDecimal;
import java.time.Instant;
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
 * Bon de réception (table {@code goods_receipt}, V015) : en ajout seul, comme le journal du
 * stock. Créé uniquement par {@link GoodsReceiptService}, dans la transaction qui écrit les
 * mouvements de stock {@code RECEIPT} correspondants. Ses lignes ({@link GoodsReceiptLine})
 * portent la relation et se lisent par {@link GoodsReceiptRepository#findLines}.
 */
@Entity
@Immutable
@Table(name = "goods_receipt")
public class GoodsReceipt {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "number", nullable = false, length = 30)
	private String number;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "purchase_order_id", nullable = false)
	private PurchaseOrder purchaseOrder;

	@Column(name = "warehouse_id", nullable = false)
	private UUID warehouseId;

	@Column(name = "received_at", nullable = false)
	private Instant receivedAt;

	@Column(name = "supplier_delivery_note", length = 50)
	private String supplierDeliveryNote;

	@Column(name = "note", length = 1000)
	private String note;

	@Column(name = "total_quantity", nullable = false)
	private int totalQuantity;

	@Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal totalAmount;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	protected GoodsReceipt() {
		// JPA
	}

	GoodsReceipt(String number, PurchaseOrder purchaseOrder, Instant receivedAt, String supplierDeliveryNote,
			String note, int totalQuantity, BigDecimal totalAmount, Instant createdAt, UUID createdBy) {
		this.number = Objects.requireNonNull(number, "number");
		this.purchaseOrder = Objects.requireNonNull(purchaseOrder, "purchaseOrder");
		this.warehouseId = purchaseOrder.getWarehouseId();
		this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
		this.supplierDeliveryNote = supplierDeliveryNote;
		this.note = note;
		this.totalQuantity = totalQuantity;
		this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
	}

	public UUID getId() {
		return this.id;
	}

	public String getNumber() {
		return this.number;
	}

	public PurchaseOrder getPurchaseOrder() {
		return this.purchaseOrder;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public Instant getReceivedAt() {
		return this.receivedAt;
	}

	public String getSupplierDeliveryNote() {
		return this.supplierDeliveryNote;
	}

	public String getNote() {
		return this.note;
	}

	public int getTotalQuantity() {
		return this.totalQuantity;
	}

	public BigDecimal getTotalAmount() {
		return this.totalAmount;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public UUID getCreatedBy() {
		return this.createdBy;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		GoodsReceipt receipt = (GoodsReceipt) other;
		return this.id != null && this.id.equals(receipt.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "GoodsReceipt[id=" + this.id + ", number=" + this.number + "]";
	}

}
