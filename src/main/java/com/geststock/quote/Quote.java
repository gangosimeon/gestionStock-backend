package com.geststock.quote;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

import com.geststock.common.jpa.AuditableEntity;

/**
 * Devis (table {@code quote}, V036). Montant total recalculé par le serveur à partir des
 * lignes ; nom du client recopié. Aucun effet sur le stock, la caisse ni le compte client.
 */
@Entity
@Table(name = "quote")
class Quote extends AuditableEntity {

	@Column(name = "number", nullable = false, updatable = false, length = 30)
	private String number;

	@Column(name = "warehouse_id", nullable = false, updatable = false)
	private UUID warehouseId;

	@Column(name = "customer_id", nullable = false)
	private UUID customerId;

	@Column(name = "customer_name", nullable = false, length = 200)
	private String customerName;

	@Column(name = "quote_date", nullable = false)
	private LocalDate quoteDate;

	@Column(name = "valid_until")
	private LocalDate validUntil;

	@Column(name = "subject", length = 500)
	private String subject;

	@Column(name = "lot_reference", length = 100)
	private String lotReference;

	@Column(name = "beneficiaries")
	private Integer beneficiaries;

	@Column(name = "delivery_address", length = 500)
	private String deliveryAddress;

	@Column(name = "contract_reference", length = 150)
	private String contractReference;

	@Column(name = "delivery_terms", length = 1000)
	private String deliveryTerms;

	@Column(name = "payment_terms", length = 1000)
	private String paymentTerms;

	@Column(name = "execution_delay", length = 200)
	private String executionDelay;

	@Column(name = "notes", length = 2000)
	private String notes;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private QuoteStatus status = QuoteStatus.DRAFT;

	@Column(name = "total", nullable = false, precision = 14, scale = 2)
	private BigDecimal total = BigDecimal.ZERO.setScale(2);

	@Column(name = "sale_id")
	private UUID saleId;

	@Column(name = "sale_number", length = 30)
	private String saleNumber;

	@Column(name = "invoiced_at")
	private Instant invoicedAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancellation_reason", length = 500)
	private String cancellationReason;

	@OneToMany(mappedBy = "quote", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNumber")
	private List<QuoteLine> lines = new ArrayList<>();

	protected Quote() {
	}

	Quote(String number, UUID warehouseId) {
		this.number = number;
		this.warehouseId = warehouseId;
	}

	/** Informations du document (tout sauf les lignes, le statut et la facturation). */
	void describe(UUID customerId, String customerName, LocalDate quoteDate, LocalDate validUntil, String subject,
			String lotReference, Integer beneficiaries, String deliveryAddress, String contractReference,
			String deliveryTerms, String paymentTerms, String executionDelay, String notes) {
		this.customerId = customerId;
		this.customerName = customerName;
		this.quoteDate = quoteDate;
		this.validUntil = validUntil;
		this.subject = subject;
		this.lotReference = lotReference;
		this.beneficiaries = beneficiaries;
		this.deliveryAddress = deliveryAddress;
		this.contractReference = contractReference;
		this.deliveryTerms = deliveryTerms;
		this.paymentTerms = paymentTerms;
		this.executionDelay = executionDelay;
		this.notes = notes;
	}

	/** Retire toutes les lignes (à écrire en base avant d'ajouter les nouvelles : numéros uniques). */
	void clearLines() {
		this.lines.clear();
		this.total = BigDecimal.ZERO.setScale(2);
	}

	void addLine(UUID productId, String sku, String name, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
		this.lines.add(new QuoteLine(this, this.lines.size() + 1, productId, sku, name, quantity, unitPrice, lineTotal));
		this.total = this.total.add(lineTotal);
	}

	void changeStatus(QuoteStatus status) {
		this.status = status;
	}

	void cancel(Instant at, String reason) {
		this.status = QuoteStatus.CANCELLED;
		this.cancelledAt = at;
		this.cancellationReason = reason;
	}

	void invoice(UUID saleId, String saleNumber, Instant at) {
		this.status = QuoteStatus.INVOICED;
		this.saleId = saleId;
		this.saleNumber = saleNumber;
		this.invoicedAt = at;
	}

	/** Émis et date de validité dépassée : présenté « expiré » (rien n'est enregistré). */
	boolean isExpired(LocalDate today) {
		return this.status == QuoteStatus.ISSUED && this.validUntil != null && this.validUntil.isBefore(today);
	}

	QuoteDisplayStatus displayStatus(LocalDate today) {
		return isExpired(today) ? QuoteDisplayStatus.EXPIRED : QuoteDisplayStatus.valueOf(this.status.name());
	}

	String getNumber() {
		return this.number;
	}

	UUID getWarehouseId() {
		return this.warehouseId;
	}

	UUID getCustomerId() {
		return this.customerId;
	}

	String getCustomerName() {
		return this.customerName;
	}

	LocalDate getQuoteDate() {
		return this.quoteDate;
	}

	LocalDate getValidUntil() {
		return this.validUntil;
	}

	String getSubject() {
		return this.subject;
	}

	String getLotReference() {
		return this.lotReference;
	}

	Integer getBeneficiaries() {
		return this.beneficiaries;
	}

	String getDeliveryAddress() {
		return this.deliveryAddress;
	}

	String getContractReference() {
		return this.contractReference;
	}

	String getDeliveryTerms() {
		return this.deliveryTerms;
	}

	String getPaymentTerms() {
		return this.paymentTerms;
	}

	String getExecutionDelay() {
		return this.executionDelay;
	}

	String getNotes() {
		return this.notes;
	}

	QuoteStatus getStatus() {
		return this.status;
	}

	BigDecimal getTotal() {
		return this.total;
	}

	UUID getSaleId() {
		return this.saleId;
	}

	String getSaleNumber() {
		return this.saleNumber;
	}

	Instant getInvoicedAt() {
		return this.invoicedAt;
	}

	Instant getCancelledAt() {
		return this.cancelledAt;
	}

	String getCancellationReason() {
		return this.cancellationReason;
	}

	List<QuoteLine> getLines() {
		return this.lines;
	}

}
