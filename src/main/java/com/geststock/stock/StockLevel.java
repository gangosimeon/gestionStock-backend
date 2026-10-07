package com.geststock.stock;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Quantité disponible d'un produit dans un dépôt (table {@code stock_level}, V013) :
 * <b>source de vérité unique du stock</b>.
 *
 * <p>Aucun accesseur public en écriture : seule {@link StockService} la fait évoluer, sous
 * verrou, en lui appliquant un {@link StockMovement}. La base refuse d'ailleurs toute
 * variation qui ne désigne pas un nouveau mouvement (trigger {@code fn_stock_level_guard}).
 */
@Entity
@Table(name = "stock_level")
@IdClass(StockLevelId.class)
public class StockLevel {

	@Id
	@Column(name = "warehouse_id", nullable = false, updatable = false)
	private UUID warehouseId;

	@Id
	@Column(name = "product_id", nullable = false, updatable = false)
	private UUID productId;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Column(name = "last_movement_id")
	private UUID lastMovementId;

	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected StockLevel() {
		// JPA : les lignes sont créées à zéro par StockService (INSERT … ON CONFLICT DO NOTHING).
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public UUID getProductId() {
		return this.productId;
	}

	public int getQuantity() {
		return this.quantity;
	}

	public UUID getLastMovementId() {
		return this.lastMovementId;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	/** Applique un mouvement déjà enregistré (réservé à {@link StockService}). */
	void apply(StockMovement movement) {
		if (!movement.getWarehouseId().equals(this.warehouseId) || !movement.getProductId().equals(this.productId)
				|| movement.getBalanceAfter() != this.quantity + movement.getQuantity()) {
			throw new IllegalStateException("Mouvement " + movement.getId() + " incohérent avec le niveau " + key());
		}
		this.quantity = movement.getBalanceAfter();
		this.lastMovementId = movement.getId();
		this.updatedAt = movement.getCreatedAt();
	}

	StockLevelId key() {
		return new StockLevelId(this.warehouseId, this.productId);
	}

}
