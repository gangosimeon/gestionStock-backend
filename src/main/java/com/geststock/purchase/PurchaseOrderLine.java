package com.geststock.purchase;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.hibernate.annotations.UuidGenerator;

/**
 * Ligne de commande fournisseur (table {@code purchase_order_line}, V015). Le coût unitaire
 * et le total de ligne sont fixés à la création ; seule la quantité reçue évolue, par
 * réception. La base vérifie au COMMIT que la quantité reçue égale la somme des lignes de
 * réception (et donc des entrées en stock).
 */
@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
	private PurchaseOrder purchaseOrder;

	@Column(name = "line_number", nullable = false, updatable = false)
	private int lineNumber;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Column(name = "product_sku", nullable = false, length = 50, updatable = false)
	private String productSku;

	@Column(name = "product_name", nullable = false, length = 200, updatable = false)
	private String productName;

	@Column(name = "ordered_quantity", nullable = false, updatable = false)
	private int orderedQuantity;

	@Column(name = "received_quantity", nullable = false)
	private int receivedQuantity;

	@Column(name = "unit_cost", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal unitCost;

	@Column(name = "line_total", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal lineTotal;

	protected PurchaseOrderLine() {
		// JPA
	}

	PurchaseOrderLine(PurchaseOrder purchaseOrder, int lineNumber, UUID productId, String productSku,
			String productName, int orderedQuantity, BigDecimal unitCost) {
		if (orderedQuantity <= 0) {
			throw new IllegalArgumentException("Quantité commandée positive requise.");
		}
		if (unitCost.signum() < 0) {
			throw new IllegalArgumentException("Coût unitaire positif ou nul requis.");
		}
		this.purchaseOrder = Objects.requireNonNull(purchaseOrder, "purchaseOrder");
		this.lineNumber = lineNumber;
		this.productId = Objects.requireNonNull(productId, "productId");
		this.productSku = Objects.requireNonNull(productSku, "productSku");
		this.productName = Objects.requireNonNull(productName, "productName");
		this.orderedQuantity = orderedQuantity;
		this.unitCost = unitCost.setScale(2, RoundingMode.UNNECESSARY);
		this.lineTotal = amountOf(orderedQuantity);
	}

	/** Ajoute une quantité reçue ; le service a déjà vérifié qu'elle ne dépasse pas le reste. */
	void receive(int quantity) {
		if (quantity <= 0 || quantity > remainingQuantity()) {
			throw new IllegalArgumentException("Quantité reçue " + quantity + " hors du reste à recevoir ("
					+ remainingQuantity() + ") de la ligne " + this.lineNumber + ".");
		}
		this.receivedQuantity += quantity;
	}

	/** Valeur d'une quantité au coût unitaire de la ligne. */
	BigDecimal amountOf(int quantity) {
		return this.unitCost.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UNNECESSARY);
	}

	public int remainingQuantity() {
		return this.orderedQuantity - this.receivedQuantity;
	}

	public boolean isFullyReceived() {
		return this.receivedQuantity == this.orderedQuantity;
	}

	public UUID getId() {
		return this.id;
	}

	public PurchaseOrder getPurchaseOrder() {
		return this.purchaseOrder;
	}

	public int getLineNumber() {
		return this.lineNumber;
	}

	public UUID getProductId() {
		return this.productId;
	}

	public String getProductSku() {
		return this.productSku;
	}

	public String getProductName() {
		return this.productName;
	}

	public int getOrderedQuantity() {
		return this.orderedQuantity;
	}

	public int getReceivedQuantity() {
		return this.receivedQuantity;
	}

	public BigDecimal getUnitCost() {
		return this.unitCost;
	}

	public BigDecimal getLineTotal() {
		return this.lineTotal;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		PurchaseOrderLine line = (PurchaseOrderLine) other;
		return this.id != null && this.id.equals(line.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "PurchaseOrderLine[id=" + this.id + ", product=" + this.productSku + ", ordered=" + this.orderedQuantity
				+ ", received=" + this.receivedQuantity + "]";
	}

}
