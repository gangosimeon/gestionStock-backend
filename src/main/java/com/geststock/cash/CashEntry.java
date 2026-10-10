package com.geststock.cash;

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
 * Écriture de caisse (table {@code cash_entry}, V018), immuable une fois écrite. Porte tout ce
 * qu'exige la traçabilité : référence, session, auteur, date, type, sens, moyen de paiement,
 * montant et pièce d'origine. Créée uniquement par {@link CashService}.
 */
@Entity
@Immutable
@Table(name = "cash_entry")
public class CashEntry {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "number", nullable = false, length = 30)
	private String number;

	@Column(name = "session_id", nullable = false)
	private UUID sessionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false, length = 30)
	private CashEntryType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "direction", nullable = false, length = 3)
	private CashEntryType.Direction direction;

	@Enumerated(EnumType.STRING)
	@Column(name = "payment_method", nullable = false, length = 20)
	private PaymentMethod paymentMethod;

	@Column(name = "amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal amount;

	@Column(name = "customer_id")
	private UUID customerId;

	@Column(name = "source_type", length = 30)
	private String sourceType;

	@Column(name = "source_id")
	private UUID sourceId;

	@Column(name = "source_reference", length = 100)
	private String sourceReference;

	@Column(name = "note", length = 500)
	private String note;

	@Column(name = "reversal_of_id")
	private UUID reversalOfId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	protected CashEntry() {
		// JPA
	}

	CashEntry(String number, UUID sessionId, CashEntryType type, PaymentMethod paymentMethod, BigDecimal amount,
			UUID customerId, String sourceType, UUID sourceId, String sourceReference, String note, UUID reversalOfId,
			Instant createdAt, UUID createdBy) {
		this.number = Objects.requireNonNull(number, "number");
		this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
		this.type = Objects.requireNonNull(type, "type");
		this.direction = type.direction();
		this.paymentMethod = Objects.requireNonNull(paymentMethod, "paymentMethod");
		this.amount = Objects.requireNonNull(amount, "amount");
		this.customerId = customerId;
		this.sourceType = sourceType;
		this.sourceId = sourceId;
		this.sourceReference = sourceReference;
		this.note = note;
		this.reversalOfId = reversalOfId;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
	}

	public UUID getId() {
		return this.id;
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getSessionId() {
		return this.sessionId;
	}

	public CashEntryType getType() {
		return this.type;
	}

	public CashEntryType.Direction getDirection() {
		return this.direction;
	}

	public PaymentMethod getPaymentMethod() {
		return this.paymentMethod;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public UUID getCustomerId() {
		return this.customerId;
	}

	public String getSourceType() {
		return this.sourceType;
	}

	public UUID getSourceId() {
		return this.sourceId;
	}

	public String getSourceReference() {
		return this.sourceReference;
	}

	public String getNote() {
		return this.note;
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
		CashEntry entry = (CashEntry) other;
		return this.id != null && this.id.equals(entry.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "CashEntry[id=" + this.id + ", number=" + this.number + ", type=" + this.type + ", amount=" + this.amount
				+ "]";
	}

}
