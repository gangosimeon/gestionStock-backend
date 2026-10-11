package com.geststock.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;

/**
 * Dépense d'un dépôt (table {@code expense}, V027). Figée une fois enregistrée : seule
 * l'annulation est permise, une fois (trigger {@code fn_expense_guard}). Écrite uniquement par
 * {@link ExpenseService}.
 */
@Entity
@Table(name = "expense")
public class Expense extends AuditableEntity {

	@Column(name = "number", nullable = false, length = 30, updatable = false)
	private String number;

	@Column(name = "warehouse_id", nullable = false, updatable = false)
	private UUID warehouseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "category", nullable = false, length = 30, updatable = false)
	private ExpenseCategory category;

	@Column(name = "label", nullable = false, length = 200, updatable = false)
	private String label;

	@Column(name = "amount", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal amount;

	@Column(name = "expense_date", nullable = false, updatable = false)
	private LocalDate expenseDate;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false, length = 20, updatable = false)
	private ExpensePaymentMethod paymentMethod;

	@Column(name = "external_reference", length = 50, updatable = false)
	private String externalReference;

	@Column(name = "note", length = 500, updatable = false)
	private String note;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ExpenseStatus status = ExpenseStatus.PAID;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	@Column(name = "cancelled_by")
	private UUID cancelledBy;

	@Column(name = "cancellation_reason", length = 500)
	private String cancellationReason;

	protected Expense() {
		// JPA
	}

	Expense(String number, UUID warehouseId, ExpenseCategory category, String label, BigDecimal amount,
			LocalDate expenseDate, ExpensePaymentMethod paymentMethod, String externalReference, String note) {
		this.number = Objects.requireNonNull(number, "number");
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
		this.category = Objects.requireNonNull(category, "category");
		this.label = Objects.requireNonNull(label, "label");
		this.amount = Objects.requireNonNull(amount, "amount");
		this.expenseDate = Objects.requireNonNull(expenseDate, "expenseDate");
		this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod");
		this.externalReference = externalReference;
		this.note = note;
		if (amount.signum() <= 0) {
			throw new IllegalArgumentException("Montant de dépense non positif.");
		}
	}

	/** Annule la dépense (une seule fois) ; elle reste visible mais sort des totaux. */
	void cancel(Instant at, UUID actor, String reason) {
		if (this.status == ExpenseStatus.CANCELLED) {
			throw new ConflictException(ErrorCode.EXPENSE_ALREADY_CANCELLED,
					ErrorCode.EXPENSE_ALREADY_CANCELLED.defaultMessage());
		}
		this.status = ExpenseStatus.CANCELLED;
		this.cancelledAt = Objects.requireNonNull(at, "at");
		this.cancelledBy = actor;
		this.cancellationReason = Objects.requireNonNull(reason, "reason");
	}

	public boolean isPaidInCash() {
		return this.paymentMethod == ExpensePaymentMethod.CASH;
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public ExpenseCategory getCategory() {
		return this.category;
	}

	public String getLabel() {
		return this.label;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public LocalDate getExpenseDate() {
		return this.expenseDate;
	}

	public ExpensePaymentMethod getPaymentMethod() {
		return this.paymentMethod;
	}

	public String getExternalReference() {
		return this.externalReference;
	}

	public String getNote() {
		return this.note;
	}

	public ExpenseStatus getStatus() {
		return this.status;
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

}
