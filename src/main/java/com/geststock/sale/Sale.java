package com.geststock.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

import com.geststock.cash.PaymentMethod;
import com.geststock.common.jpa.AuditableEntity;
import com.geststock.customer.PriceCategory;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;

/**
 * Vente (table {@code sale}, V017). Montants calculés par le serveur et figés à
 * l'enregistrement (la base refuse leur modification) ; seule l'annulation change la vente.
 * Créée et annulée uniquement par {@link SaleService}.
 */
@Entity
@Table(name = "sale")
public class Sale extends AuditableEntity {

	@Column(name = "number", nullable = false, length = 30, updatable = false)
	private String number;

	@Column(name = "warehouse_id", nullable = false, updatable = false)
	private UUID warehouseId;

	@Column(name = "customer_id", updatable = false)
	private UUID customerId;

	@Column(name = "customer_name", length = 200, updatable = false)
	private String customerName;

	@Enumerated(EnumType.STRING)
	@Column(name = "price_type", nullable = false, length = 20, updatable = false)
	private PriceCategory priceType;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SaleStatus status = SaleStatus.COMPLETED;

	@Column(name = "subtotal", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal subtotal;

	@Column(name = "discount_total", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal discountTotal;

	@Column(name = "total", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal total;

	@Column(name = "cost_total", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal costTotal;

	@Column(name = "profit", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal profit;

	@Column(name = "paid_amount", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal paidAmount;

	@Column(name = "credit_amount", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal creditAmount;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", length = 20, updatable = false)
	private PaymentMethod paymentMethod;

	@Column(name = "note", length = 500, updatable = false)
	private String note;

	@Column(name = "cash_received", precision = 14, scale = 2, updatable = false)
	private BigDecimal cashReceived;

	@Column(name = "change_given", precision = 14, scale = 2, updatable = false)
	private BigDecimal changeGiven;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancelled_by")
	private UUID cancelledBy;

	@Column(name = "cancellation_reason", length = 500)
	private String cancellationReason;

	@OneToMany(mappedBy = "sale")
	@OrderBy("lineNumber")
	@BatchSize(size = 50)
	private List<SaleLine> lines = new ArrayList<>();

	protected Sale() {
		// JPA
	}

	Sale(String number, UUID warehouseId, UUID customerId, String customerName, PriceCategory priceType,
			BigDecimal subtotal, BigDecimal discountTotal, BigDecimal costTotal, BigDecimal paidAmount,
			PaymentMethod paymentMethod, String note) {
		this.number = Objects.requireNonNull(number, "number");
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
		this.customerId = customerId;
		this.customerName = customerName;
		this.priceType = Objects.requireNonNull(priceType, "priceType");
		this.subtotal = Objects.requireNonNull(subtotal, "subtotal");
		this.discountTotal = Objects.requireNonNull(discountTotal, "discountTotal");
		this.total = subtotal.subtract(discountTotal);
		this.costTotal = Objects.requireNonNull(costTotal, "costTotal");
		this.profit = this.total.subtract(costTotal);
		this.paidAmount = Objects.requireNonNull(paidAmount, "paidAmount");
		this.creditAmount = this.total.subtract(paidAmount);
		this.paymentMethod = paymentMethod;
		this.note = note;
		if (paidAmount.signum() < 0 || this.creditAmount.signum() < 0) {
			throw new IllegalArgumentException("Montant payé hors de [0, total].");
		}
		if (this.creditAmount.signum() > 0 && customerId == null) {
			throw new IllegalArgumentException("Un reste dû exige un client.");
		}
	}

	/** Rattache en mémoire une ligne persistée (la relation est portée par la ligne). */
	void attach(SaleLine line) {
		this.lines.add(line);
	}

	/** Annulation : une seule fois. Stock et créance sont rétablis par {@link SaleService}. */
	void cancel(Instant at, UUID actor, String reason) {
		if (this.status != SaleStatus.COMPLETED) {
			throw new ConflictException(ErrorCode.SALE_NOT_CANCELLABLE, ErrorCode.SALE_NOT_CANCELLABLE.defaultMessage());
		}
		this.status = SaleStatus.CANCELLED;
		this.cancelledAt = Objects.requireNonNull(at, "at");
		this.cancelledBy = actor;
		this.cancellationReason = reason;
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public UUID getCustomerId() {
		return this.customerId;
	}

	public String getCustomerName() {
		return this.customerName;
	}

	public PriceCategory getPriceType() {
		return this.priceType;
	}

	public SaleStatus getStatus() {
		return this.status;
	}

	public BigDecimal getSubtotal() {
		return this.subtotal;
	}

	public BigDecimal getDiscountTotal() {
		return this.discountTotal;
	}

	public BigDecimal getTotal() {
		return this.total;
	}

	public BigDecimal getCostTotal() {
		return this.costTotal;
	}

	public BigDecimal getProfit() {
		return this.profit;
	}

	/**
	 * Espèces remises par le client : la monnaie rendue est la différence avec le montant payé.
	 * Avant l'enregistrement seulement (colonnes immuables).
	 */
	void recordCashTender(BigDecimal received) {
		if (getId() != null) {
			// Après persistance, la valeur resterait en mémoire : colonnes exclues de tout UPDATE.
			throw new IllegalStateException("Montant reçu à renseigner avant l'enregistrement de la vente.");
		}
		if (received.compareTo(this.paidAmount) < 0) {
			throw new IllegalArgumentException("Montant reçu inférieur au montant payé.");
		}
		this.cashReceived = received;
		this.changeGiven = received.subtract(this.paidAmount);
	}

	public BigDecimal getCashReceived() {
		return this.cashReceived;
	}

	public BigDecimal getChangeGiven() {
		return this.changeGiven;
	}

	public BigDecimal getPaidAmount() {
		return this.paidAmount;
	}

	public BigDecimal getCreditAmount() {
		return this.creditAmount;
	}

	public PaymentMethod getPaymentMethod() {
		return this.paymentMethod;
	}

	public String getNote() {
		return this.note;
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

	public List<SaleLine> getLines() {
		return Collections.unmodifiableList(this.lines);
	}

}
