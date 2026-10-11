package com.geststock.purchase;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Commande fournisseur (table {@code purchase_order}, V015). Agrégat : ses lignes ne changent
 * qu'à travers elle. Montants calculés ici, jamais repris du client.
 *
 * <p>Seul {@link GoodsReceiptService} enregistre une réception ({@link #recordReceipt}), dans
 * la transaction qui écrit aussi les mouvements de stock.
 */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder extends AuditableEntity {

	@Column(name = "number", nullable = false, length = 30, updatable = false)
	private String number;

	@Column(name = "supplier_id", nullable = false, updatable = false)
	private UUID supplierId;

	@Column(name = "supplier_name", nullable = false, length = 200, updatable = false)
	private String supplierName;

	@Column(name = "warehouse_id", nullable = false, updatable = false)
	private UUID warehouseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PurchaseOrderStatus status = PurchaseOrderStatus.ORDERED;

	@Column(name = "expected_delivery_date")
	private LocalDate expectedDeliveryDate;

	@Column(name = "supplier_reference", length = 50)
	private String supplierReference;

	@Column(name = "note", length = 1000)
	private String note;

	@Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal totalAmount = BigDecimal.ZERO.setScale(2);

	@Column(name = "received_amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal receivedAmount = BigDecimal.ZERO.setScale(2);

	/** Somme des imputations de paiements (V025), tenue par {@link SupplierPaymentService}. */
	@Column(name = "paid_amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal paidAmount = BigDecimal.ZERO.setScale(2);

	@Column(name = "last_receipt_at")
	private Instant lastReceiptAt;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "closed_by")
	private UUID closedBy;

	@Column(name = "close_reason", length = 500)
	private String closeReason;

	// Facture fournisseur (V026) : au plus une, figée une fois enregistrée.
	@Column(name = "invoice_number", length = 50)
	private String invoiceNumber;

	@Column(name = "invoice_date")
	private LocalDate invoiceDate;

	@Column(name = "invoiced_amount", precision = 14, scale = 2)
	private BigDecimal invoicedAmount;

	@Column(name = "invoiced_at")
	private Instant invoicedAt;

	@Column(name = "invoiced_by")
	private UUID invoicedBy;

	@OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL)
	@OrderBy("lineNumber")
	@BatchSize(size = 50)
	private List<PurchaseOrderLine> lines = new ArrayList<>();

	protected PurchaseOrder() {
		// JPA
	}

	PurchaseOrder(String number, UUID supplierId, String supplierName, UUID warehouseId,
			LocalDate expectedDeliveryDate, String supplierReference, String note) {
		this.number = Objects.requireNonNull(number, "number");
		this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
		this.supplierName = Objects.requireNonNull(supplierName, "supplierName");
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
		this.expectedDeliveryDate = expectedDeliveryDate;
		this.supplierReference = supplierReference;
		this.note = note;
	}

	/** Ajoute une ligne à une commande en cours de création ; le total est recalculé. */
	PurchaseOrderLine addLine(UUID productId, String productSku, String productName, int quantity,
			BigDecimal unitCost) {
		if (this.status != PurchaseOrderStatus.ORDERED || this.lastReceiptAt != null) {
			throw new IllegalStateException("Lignes modifiables uniquement à la création de la commande.");
		}
		PurchaseOrderLine line = new PurchaseOrderLine(this, this.lines.size() + 1, productId, productSku, productName,
				quantity, unitCost);
		this.lines.add(line);
		this.totalAmount = this.totalAmount.add(line.getLineTotal());
		return line;
	}

	/**
	 * Enregistre une réception déjà appliquée aux lignes ({@link PurchaseOrderLine#receive}) :
	 * valeur reçue, date, puis statut (reçue en totalité ou en partie).
	 */
	void recordReceipt(BigDecimal amount, Instant receivedAt) {
		if (!this.status.isReceivable()) {
			throw notReceivable();
		}
		this.receivedAmount = this.receivedAmount.add(amount);
		this.lastReceiptAt = this.lastReceiptAt == null || receivedAt.isAfter(this.lastReceiptAt) ? receivedAt
				: this.lastReceiptAt;
		this.status = this.lines.stream().allMatch(PurchaseOrderLine::isFullyReceived) ? PurchaseOrderStatus.RECEIVED
				: PurchaseOrderStatus.PARTIALLY_RECEIVED;
	}

	/**
	 * Annulation : seulement si rien n'a été reçu ni payé (un acompte versé n'aurait plus de
	 * contrepartie).
	 */
	void cancel(Instant at, UUID actor, String reason) {
		if (this.status != PurchaseOrderStatus.ORDERED) {
			throw new ConflictException(ErrorCode.PURCHASE_ORDER_NOT_CANCELLABLE,
					ErrorCode.PURCHASE_ORDER_NOT_CANCELLABLE.defaultMessage());
		}
		requirePaidCoveredBy(this.receivedAmount);
		close(PurchaseOrderStatus.CANCELLED, at, actor, reason);
	}

	/**
	 * Solde du reliquat : la commande reçue en partie n'attend plus rien. Refusé si les
	 * paiements dépassent déjà la valeur reçue.
	 */
	void closeRemainder(Instant at, UUID actor, String reason) {
		if (this.status != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
			throw new ConflictException(ErrorCode.PURCHASE_ORDER_NOT_CLOSABLE,
					ErrorCode.PURCHASE_ORDER_NOT_CLOSABLE.defaultMessage());
		}
		requirePaidCoveredBy(this.receivedAmount);
		close(PurchaseOrderStatus.CLOSED, at, actor, reason);
	}

	// --- Paiements ----------------------------------------------------------------------------

	/**
	 * Montant dû au fournisseur pour cette commande : le total commandé tant qu'elle est en cours
	 * ou reçue (un acompte est possible), la valeur reçue une fois soldée, rien si annulée.
	 */
	BigDecimal payableAmount() {
		return switch (this.status) {
			case ORDERED, PARTIALLY_RECEIVED, RECEIVED -> this.totalAmount;
			case CLOSED -> this.receivedAmount;
			case CANCELLED -> BigDecimal.ZERO.setScale(2);
		};
	}

	/** Reste à payer (jamais négatif : pas de trop-payé). */
	BigDecimal remainingToPay() {
		return payableAmount().subtract(this.paidAmount).max(BigDecimal.ZERO).setScale(2);
	}

	/** Impute un paiement ; seul {@link SupplierPaymentService} l'appelle, sous verrou de la commande. */
	void recordPayment(BigDecimal amount) {
		if (amount.signum() <= 0 || amount.compareTo(remainingToPay()) > 0) {
			throw new BusinessRuleException(ErrorCode.PURCHASE_ORDER_PAYMENT_EXCEEDS_REMAINING,
					ErrorCode.PURCHASE_ORDER_PAYMENT_EXCEEDS_REMAINING.defaultMessage(),
					Map.<String, Object>of("orderNumber", this.number, "remainingToPay", remainingToPay(), "requested", amount));
		}
		this.paidAmount = this.paidAmount.add(amount).setScale(2);
	}

	// --- Facture -----------------------------------------------------------------------------

	/**
	 * Enregistre la facture du fournisseur : seulement quand plus rien n'est attendu (reçue ou
	 * soldée), une seule fois. Le montant facturé est la valeur reçue.
	 */
	void recordInvoice(String number, LocalDate date, Instant at, UUID actor) {
		if (this.invoiceNumber != null) {
			throw new ConflictException(ErrorCode.PURCHASE_ORDER_ALREADY_INVOICED,
					ErrorCode.PURCHASE_ORDER_ALREADY_INVOICED.defaultMessage());
		}
		if (this.status != PurchaseOrderStatus.RECEIVED && this.status != PurchaseOrderStatus.CLOSED) {
			throw new ConflictException(ErrorCode.PURCHASE_ORDER_NOT_INVOICEABLE,
					ErrorCode.PURCHASE_ORDER_NOT_INVOICEABLE.defaultMessage());
		}
		this.invoiceNumber = Objects.requireNonNull(number, "number");
		this.invoiceDate = Objects.requireNonNull(date, "date");
		this.invoicedAmount = this.receivedAmount;
		this.invoicedAt = Objects.requireNonNull(at, "at");
		this.invoicedBy = actor;
	}

	private void requirePaidCoveredBy(BigDecimal due) {
		if (this.paidAmount.compareTo(due) > 0) {
			throw new ConflictException(ErrorCode.PURCHASE_ORDER_PREPAID,
					ErrorCode.PURCHASE_ORDER_PREPAID.defaultMessage());
		}
	}

	static ConflictException notReceivable() {
		return new ConflictException(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE,
				ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE.defaultMessage());
	}

	private void close(PurchaseOrderStatus target, Instant at, UUID actor, String reason) {
		this.status = target;
		this.closedAt = Objects.requireNonNull(at, "at");
		this.closedBy = actor;
		this.closeReason = reason;
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getSupplierId() {
		return this.supplierId;
	}

	public String getSupplierName() {
		return this.supplierName;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public PurchaseOrderStatus getStatus() {
		return this.status;
	}

	public LocalDate getExpectedDeliveryDate() {
		return this.expectedDeliveryDate;
	}

	public String getSupplierReference() {
		return this.supplierReference;
	}

	public String getNote() {
		return this.note;
	}

	public BigDecimal getTotalAmount() {
		return this.totalAmount;
	}

	public String getInvoiceNumber() {
		return this.invoiceNumber;
	}

	public LocalDate getInvoiceDate() {
		return this.invoiceDate;
	}

	public BigDecimal getInvoicedAmount() {
		return this.invoicedAmount;
	}

	public Instant getInvoicedAt() {
		return this.invoicedAt;
	}

	public UUID getInvoicedBy() {
		return this.invoicedBy;
	}

	public BigDecimal getPaidAmount() {
		return this.paidAmount;
	}

	public BigDecimal getReceivedAmount() {
		return this.receivedAmount;
	}

	public Instant getLastReceiptAt() {
		return this.lastReceiptAt;
	}

	public Instant getClosedAt() {
		return this.closedAt;
	}

	public UUID getClosedBy() {
		return this.closedBy;
	}

	public String getCloseReason() {
		return this.closeReason;
	}

	public List<PurchaseOrderLine> getLines() {
		return Collections.unmodifiableList(this.lines);
	}

}
