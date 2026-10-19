package com.geststock.stock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import com.geststock.common.jpa.AuditableEntity;

/**
 * Transfert entre deux dépôts (table {@code stock_transfer}, V043 ; lignes V044) : pièce
 * numérotée du reçu. Noms des dépôts et des produits recopiés. Ne porte aucune quantité en
 * stock : ses mouvements ({@code source_type} TRANSFER, {@code source_id} = cette pièce) le font.
 */
@Entity
@Table(name = "stock_transfer")
class StockTransfer extends AuditableEntity {

	@Column(name = "number", nullable = false, updatable = false, length = 30)
	private String number;

	@Column(name = "from_warehouse_id", nullable = false, updatable = false)
	private UUID fromWarehouseId;

	@Column(name = "from_warehouse_name", nullable = false, updatable = false, length = 100)
	private String fromWarehouseName;

	@Column(name = "to_warehouse_id", nullable = false, updatable = false)
	private UUID toWarehouseId;

	@Column(name = "to_warehouse_name", nullable = false, updatable = false, length = 100)
	private String toWarehouseName;

	@Column(name = "note", updatable = false, length = 500)
	private String note;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private StockTransferStatus status = StockTransferStatus.COMPLETED;

	@Column(name = "returned_at")
	private Instant returnedAt;

	@Column(name = "returned_by")
	private UUID returnedBy;

	@Column(name = "return_reason", length = 500)
	private String returnReason;

	@OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNumber")
	@BatchSize(size = 50)
	private List<StockTransferLine> lines = new ArrayList<>();

	protected StockTransfer() {
	}

	StockTransfer(String number, UUID fromWarehouseId, String fromWarehouseName, UUID toWarehouseId,
			String toWarehouseName, String note) {
		this.number = number;
		this.fromWarehouseId = fromWarehouseId;
		this.fromWarehouseName = fromWarehouseName;
		this.toWarehouseId = toWarehouseId;
		this.toWarehouseName = toWarehouseName;
		this.note = note;
	}

	void addLine(UUID productId, String productSku, String productName, int quantity) {
		this.lines.add(new StockTransferLine(this, this.lines.size() + 1, productId, productSku, productName, quantity));
	}

	void markReturned(Instant at, UUID by, String reason) {
		this.status = StockTransferStatus.RETURNED;
		this.returnedAt = at;
		this.returnedBy = by;
		this.returnReason = reason;
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getFromWarehouseId() {
		return this.fromWarehouseId;
	}

	public String getFromWarehouseName() {
		return this.fromWarehouseName;
	}

	public UUID getToWarehouseId() {
		return this.toWarehouseId;
	}

	public String getToWarehouseName() {
		return this.toWarehouseName;
	}

	public String getNote() {
		return this.note;
	}

	public StockTransferStatus getStatus() {
		return this.status;
	}

	public Instant getReturnedAt() {
		return this.returnedAt;
	}

	public UUID getReturnedBy() {
		return this.returnedBy;
	}

	public String getReturnReason() {
		return this.returnReason;
	}

	List<StockTransferLine> getLines() {
		return List.copyOf(this.lines);
	}

	/** Somme des quantités des lignes. */
	int getTotalQuantity() {
		return this.lines.stream().mapToInt(StockTransferLine::getQuantity).sum();
	}

}
