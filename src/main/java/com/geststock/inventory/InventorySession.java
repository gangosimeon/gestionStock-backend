package com.geststock.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;

/**
 * Inventaire d'un dépôt (table {@code inventory_session}, V019). Porte le workflow
 * ({@link InventoryStatus}) : chaque action vérifie l'étape, la base l'impose aussi. Terminé ou
 * annulé, il est figé.
 */
@Entity
@Table(name = "inventory_session")
public class InventorySession extends AuditableEntity {

	@Column(name = "number", nullable = false, length = 30, updatable = false)
	private String number;

	@Column(name = "warehouse_id", nullable = false, updatable = false)
	private UUID warehouseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private InventoryStatus status = InventoryStatus.DRAFT;

	@Enumerated(EnumType.STRING)
	@Column(name = "scope", nullable = false, length = 20, updatable = false)
	private InventoryScope scope;

	@Column(name = "category_id", updatable = false)
	private UUID categoryId;

	@Column(name = "note", length = 1000)
	private String note;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "started_by")
	private UUID startedBy;

	@Column(name = "submitted_at")
	private Instant submittedAt;

	@Column(name = "submitted_by")
	private UUID submittedBy;

	@Column(name = "validated_at")
	private Instant validatedAt;

	@Column(name = "validated_by")
	private UUID validatedBy;

	@Column(name = "validation_comment", length = 1000)
	private String validationComment;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancelled_by")
	private UUID cancelledBy;

	@Column(name = "cancellation_reason", length = 500)
	private String cancellationReason;

	@OneToMany(mappedBy = "inventory", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNumber")
	@BatchSize(size = 50)
	private List<InventoryLine> lines = new ArrayList<>();

	protected InventorySession() {
		// JPA
	}

	InventorySession(String number, UUID warehouseId, InventoryScope scope, UUID categoryId, String note) {
		this.number = Objects.requireNonNull(number, "number");
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
		this.scope = Objects.requireNonNull(scope, "scope");
		this.categoryId = categoryId;
		this.note = note;
	}

	// --- Périmètre (brouillon) ------------------------------------------------------------

	/** Ajoute un produit au périmètre ; sans effet s'il y figure déjà. */
	void addProduct(UUID productId, String sku, String name) {
		require(this.status == InventoryStatus.DRAFT, "ajouter un produit");
		if (line(productId).isPresent()) {
			return;
		}
		int next = this.lines.stream().mapToInt(InventoryLine::getLineNumber).max().orElse(0) + 1;
		this.lines.add(new InventoryLine(this, next, productId, sku, name));
	}

	void removeProduct(UUID productId) {
		require(this.status == InventoryStatus.DRAFT, "retirer un produit");
		this.lines.removeIf(line -> line.getProductId().equals(productId));
	}

	// --- Workflow ---------------------------------------------------------------------------

	void start(Instant at, UUID by) {
		require(this.status == InventoryStatus.DRAFT, "démarrer");
		this.status = InventoryStatus.IN_PROGRESS;
		this.startedAt = Objects.requireNonNull(at, "at");
		this.startedBy = by;
	}

	/** Enregistre un comptage ; le premier fait passer l'inventaire en COUNTING. */
	void count(InventoryLine line, int counted, int theoretical, Instant at, UUID by, String lineNote) {
		require(this.status.acceptsCounts(), "saisir un comptage");
		line.count(counted, theoretical, at, by, lineNote);
		this.status = InventoryStatus.COUNTING;
	}

	/** Clôt le comptage : toutes les lignes doivent être comptées (pas d'article « réputé conforme »). */
	void submit(Instant at, UUID by) {
		require(this.status == InventoryStatus.COUNTING, "soumettre à validation");
		long missing = this.lines.size() - countedCount();
		if (missing > 0) {
			throw new BusinessRuleException(ErrorCode.INVENTORY_LINES_NOT_COUNTED,
					missing + " produit(s) non compté(s) : tous les produits de l'inventaire doivent être comptés.",
					Map.of("uncounted", missing));
		}
		this.status = InventoryStatus.VALIDATION;
		this.submittedAt = Objects.requireNonNull(at, "at");
		this.submittedBy = by;
	}

	/** Le valideur renvoie l'inventaire au comptage (recomptage demandé). */
	void reopen() {
		require(this.status == InventoryStatus.VALIDATION, "renvoyer au comptage");
		this.status = InventoryStatus.COUNTING;
		this.submittedAt = null;
		this.submittedBy = null;
	}

	/** Validation : les écarts ont été appliqués au stock (lignes liées à leur mouvement). */
	void complete(Instant at, UUID by, String comment) {
		require(this.status == InventoryStatus.VALIDATION, "valider");
		this.status = InventoryStatus.COMPLETED;
		this.validatedAt = Objects.requireNonNull(at, "at");
		this.validatedBy = Objects.requireNonNull(by, "by");
		this.validationComment = comment;
	}

	void cancel(Instant at, UUID by, String reason) {
		require(!this.status.isFinal(), "annuler");
		this.status = InventoryStatus.CANCELLED;
		this.cancelledAt = Objects.requireNonNull(at, "at");
		this.cancelledBy = by;
		this.cancellationReason = reason;
	}

	private void require(boolean allowed, String action) {
		if (!allowed) {
			throw new ConflictException(ErrorCode.INVENTORY_INVALID_TRANSITION,
					"Impossible de " + action + " : l'inventaire " + this.number + " est à l'étape " + this.status + ".");
		}
	}

	// --- Lecture ------------------------------------------------------------------------------

	Optional<InventoryLine> line(UUID productId) {
		return this.lines.stream().filter(line -> line.getProductId().equals(productId)).findFirst();
	}

	public long countedCount() {
		return this.lines.stream().filter(InventoryLine::isCounted).count();
	}

	public int totalDifference() {
		return this.lines.stream().filter(InventoryLine::isCounted).mapToInt(InventoryLine::getDifference).sum();
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public InventoryStatus getStatus() {
		return this.status;
	}

	public InventoryScope getScope() {
		return this.scope;
	}

	public UUID getCategoryId() {
		return this.categoryId;
	}

	public String getNote() {
		return this.note;
	}

	public Instant getStartedAt() {
		return this.startedAt;
	}

	public UUID getStartedBy() {
		return this.startedBy;
	}

	public Instant getSubmittedAt() {
		return this.submittedAt;
	}

	public UUID getSubmittedBy() {
		return this.submittedBy;
	}

	public Instant getValidatedAt() {
		return this.validatedAt;
	}

	public UUID getValidatedBy() {
		return this.validatedBy;
	}

	public String getValidationComment() {
		return this.validationComment;
	}

	public Instant getCancelledAt() {
		return this.cancelledAt;
	}

	public UUID getCancelledBy() {
		return this.cancelledBy;
	}

	public String getCancellationReason() {
		return this.cancellationReason;
	}

	public List<InventoryLine> getLines() {
		return Collections.unmodifiableList(this.lines);
	}

}
