package com.geststock.purchase;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import com.geststock.cash.PaymentMethod;

/**
 * Paiement à un fournisseur (table {@code supplier_payment}, V025), immuable une fois écrit.
 * Réparti sur des commandes par {@link SupplierPaymentAllocation}. Créé uniquement par
 * {@link SupplierPaymentService}.
 */
@Entity
@Immutable
@Table(name = "supplier_payment")
public class SupplierPayment {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "number", nullable = false, length = 30)
	private String number;

	@Column(name = "supplier_id", nullable = false)
	private UUID supplierId;

	@Column(name = "payment_date", nullable = false)
	private LocalDate paymentDate;

	@Column(name = "amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false, length = 20)
	private PaymentMethod paymentMethod;

	@Column(name = "external_reference", length = 50)
	private String externalReference;

	@Column(name = "note", length = 500)
	private String note;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "created_by")
	private UUID createdBy;

	/** Dépôt du paiement (celui de ses commandes et de sa caisse) ; {@code null} : historique ambigu. */
	@Column(name = "warehouse_id", updatable = false)
	private UUID warehouseId;

	protected SupplierPayment() {
		// JPA
	}

	SupplierPayment(String number, UUID supplierId, LocalDate paymentDate, BigDecimal amount,
			PaymentMethod paymentMethod, String externalReference, String note, Instant createdAt, UUID createdBy,
			UUID warehouseId) {
		this.number = Objects.requireNonNull(number, "number");
		this.supplierId = Objects.requireNonNull(supplierId, "supplierId");
		this.paymentDate = Objects.requireNonNull(paymentDate, "paymentDate");
		this.amount = Objects.requireNonNull(amount, "amount");
		this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod");
		this.externalReference = externalReference;
		this.note = note;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.createdBy = createdBy;
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public UUID getId() {
		return this.id;
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getSupplierId() {
		return this.supplierId;
	}

	public LocalDate getPaymentDate() {
		return this.paymentDate;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public PaymentMethod getPaymentMethod() {
		return this.paymentMethod;
	}

	public String getExternalReference() {
		return this.externalReference;
	}

	public String getNote() {
		return this.note;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public UUID getCreatedBy() {
		return this.createdBy;
	}

}
