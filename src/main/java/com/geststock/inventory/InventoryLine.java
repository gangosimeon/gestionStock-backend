package com.geststock.inventory;

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
import org.hibernate.annotations.UuidGenerator;

/**
 * Ligne d'inventaire (table {@code inventory_line}, V019) : un produit du périmètre, sa
 * quantité théorique relevée au comptage, sa quantité comptée et l'écart. Modifiable
 * seulement à l'étape prévue (contrôlé par l'inventaire et par la base).
 */
@Entity
@Table(name = "inventory_line")
public class InventoryLine {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "inventory_id", nullable = false, updatable = false)
	private InventorySession inventory;

	@Column(name = "line_number", nullable = false, updatable = false)
	private int lineNumber;

	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Column(name = "product_sku", nullable = false, length = 50, updatable = false)
	private String productSku;

	@Column(name = "product_name", nullable = false, length = 200, updatable = false)
	private String productName;

	@Column(name = "theoretical_quantity")
	private Integer theoreticalQuantity;

	@Column(name = "counted_quantity")
	private Integer countedQuantity;

	@Column(name = "difference")
	private Integer difference;

	@Column(name = "counted_at")
	private Instant countedAt;

	@Column(name = "counted_by")
	private UUID countedBy;

	@Column(name = "note", length = 500)
	private String note;

	@Column(name = "stock_movement_id")
	private UUID stockMovementId;

	protected InventoryLine() {
		// JPA
	}

	InventoryLine(InventorySession inventory, int lineNumber, UUID productId, String productSku, String productName) {
		this.inventory = Objects.requireNonNull(inventory, "inventory");
		this.lineNumber = lineNumber;
		this.productId = Objects.requireNonNull(productId, "productId");
		this.productSku = Objects.requireNonNull(productSku, "productSku");
		this.productName = Objects.requireNonNull(productName, "productName");
	}

	/** Comptage (ou recomptage) : la quantité théorique est celle relevée à cet instant. */
	void count(int counted, int theoretical, Instant at, UUID by, String newNote) {
		if (counted < 0 || theoretical < 0) {
			throw new IllegalArgumentException("Quantités négatives.");
		}
		this.countedQuantity = counted;
		this.theoreticalQuantity = theoretical;
		this.difference = counted - theoretical;
		this.countedAt = Objects.requireNonNull(at, "at");
		this.countedBy = Objects.requireNonNull(by, "by");
		this.note = newNote;
	}

	/** Mouvement de stock qui a appliqué l'écart (validation). */
	void applied(UUID movementId) {
		this.stockMovementId = Objects.requireNonNull(movementId, "movementId");
	}

	public boolean isCounted() {
		return this.countedQuantity != null;
	}

	public UUID getId() {
		return this.id;
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

	public Integer getTheoreticalQuantity() {
		return this.theoreticalQuantity;
	}

	public Integer getCountedQuantity() {
		return this.countedQuantity;
	}

	public Integer getDifference() {
		return this.difference;
	}

	public Instant getCountedAt() {
		return this.countedAt;
	}

	public UUID getCountedBy() {
		return this.countedBy;
	}

	public String getNote() {
		return this.note;
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
		InventoryLine line = (InventoryLine) other;
		return this.id != null && this.id.equals(line.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "InventoryLine[product=" + this.productSku + ", counted=" + this.countedQuantity + ", difference="
				+ this.difference + "]";
	}

}
