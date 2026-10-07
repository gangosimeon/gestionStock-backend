package com.geststock.stock;

import java.io.Serializable;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Clé de {@link StockLevel} : couple dépôt × produit. L'ordre naturel (dépôt puis produit)
 * est l'ordre de verrouillage : deux transactions verrouillent toujours dans le même ordre,
 * ce qui exclut les interblocages.
 */
public class StockLevelId implements Serializable, Comparable<StockLevelId> {

	private static final Comparator<StockLevelId> ORDER = Comparator.comparing(StockLevelId::getWarehouseId)
		.thenComparing(StockLevelId::getProductId);

	private UUID warehouseId;

	private UUID productId;

	protected StockLevelId() {
		// JPA
	}

	public StockLevelId(UUID warehouseId, UUID productId) {
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
		this.productId = Objects.requireNonNull(productId, "productId");
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public UUID getProductId() {
		return this.productId;
	}

	@Override
	public int compareTo(StockLevelId other) {
		return ORDER.compare(this, other);
	}

	@Override
	public boolean equals(Object other) {
		return this == other || (other instanceof StockLevelId id && this.warehouseId.equals(id.warehouseId)
				&& this.productId.equals(id.productId));
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.warehouseId, this.productId);
	}

	@Override
	public String toString() {
		return this.warehouseId + "/" + this.productId;
	}

}
