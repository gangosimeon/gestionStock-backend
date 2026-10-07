package com.geststock.stock;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.Hibernate;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * Mouvement de stock (table {@code stock_movement}, V013) : entrée du journal, immuable une
 * fois écrite (trigger {@code fn_prevent_modification}). Une erreur se corrige par un
 * mouvement inverse ({@link StockMovementType#REVERSAL}), jamais par modification.
 *
 * <p>Créé uniquement par {@link StockService}.
 */
@Entity
@Immutable
@Table(name = "stock_movement")
public class StockMovement {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "warehouse_id", nullable = false)
	private UUID warehouseId;

	@Column(name = "product_id", nullable = false)
	private UUID productId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 30)
	private StockMovementType type;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Column(name = "balance_after", nullable = false)
	private int balanceAfter;

	@Column(name = "unit_cost", precision = 14, scale = 2)
	private BigDecimal unitCost;

	@Column(name = "reason", length = 500)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, length = 30)
	private StockSourceType sourceType;

	@Column(name = "source_id")
	private UUID sourceId;

	@Column(name = "source_reference", length = 100)
	private String sourceReference;

	@Column(name = "correlation_id")
	private UUID correlationId;

	@Column(name = "reversal_of_id")
	private UUID reversalOfId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	protected StockMovement() {
		// JPA
	}

	StockMovement(StockMovementCommand command, int balanceAfter, BigDecimal unitCost, Instant createdAt,
			UUID createdBy) {
		this.warehouseId = command.warehouseId();
		this.productId = command.productId();
		this.type = command.type();
		this.quantity = command.quantity();
		this.balanceAfter = balanceAfter;
		this.unitCost = unitCost;
		this.reason = command.reason();
		this.sourceType = command.sourceType();
		this.sourceId = command.sourceId();
		this.sourceReference = command.sourceReference();
		this.correlationId = command.correlationId();
		this.reversalOfId = command.reversalOfId();
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public UUID getProductId() {
		return this.productId;
	}

	public StockMovementType getType() {
		return this.type;
	}

	/** Variation signée (entrée &gt; 0, sortie &lt; 0). */
	public int getQuantity() {
		return this.quantity;
	}

	/** Quantité du couple dépôt × produit après ce mouvement. */
	public int getBalanceAfter() {
		return this.balanceAfter;
	}

	public BigDecimal getUnitCost() {
		return this.unitCost;
	}

	public String getReason() {
		return this.reason;
	}

	public StockSourceType getSourceType() {
		return this.sourceType;
	}

	public UUID getSourceId() {
		return this.sourceId;
	}

	public String getSourceReference() {
		return this.sourceReference;
	}

	public UUID getCorrelationId() {
		return this.correlationId;
	}

	public UUID getReversalOfId() {
		return this.reversalOfId;
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
		StockMovement movement = (StockMovement) other;
		return this.id != null && this.id.equals(movement.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "StockMovement[id=" + this.id + ", type=" + this.type + ", quantity=" + this.quantity + "]";
	}

}
