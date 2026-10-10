package com.geststock.customer;

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
 * Écriture du compte client (table {@code customer_account_entry}, V016), immuable une fois
 * écrite (trigger {@code fn_prevent_modification}). Une erreur se corrige par une écriture
 * inverse (annulation de vente, avoir), jamais par modification.
 *
 * <p>Créée uniquement par {@link CustomerAccountService}.
 */
@Entity
@Immutable
@Table(name = "customer_account_entry")
public class CustomerAccountEntry {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "customer_id", nullable = false)
	private UUID customerId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false, length = 30)
	private CustomerAccountEntryType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "direction", nullable = false, length = 6)
	private CustomerAccountEntryType.Direction direction;

	@Column(name = "amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal amount;

	@Column(name = "balance_after", nullable = false, precision = 14, scale = 2)
	private BigDecimal balanceAfter;

	@Column(name = "source_type", nullable = false, length = 30)
	private String sourceType;

	@Column(name = "source_id", nullable = false)
	private UUID sourceId;

	@Column(name = "source_reference", length = 100)
	private String sourceReference;

	@Column(name = "note", length = 500)
	private String note;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	protected CustomerAccountEntry() {
		// JPA
	}

	CustomerAccountEntry(UUID customerId, CustomerAccountEntryType type, BigDecimal amount, BigDecimal balanceAfter,
			UUID sourceId, String sourceReference, String note, Instant createdAt, UUID createdBy) {
		this.customerId = Objects.requireNonNull(customerId, "customerId");
		this.type = Objects.requireNonNull(type, "type");
		this.direction = type.direction();
		this.amount = Objects.requireNonNull(amount, "amount");
		this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
		this.sourceType = type.sourceType();
		this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
		this.sourceReference = sourceReference;
		this.note = note;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getCustomerId() {
		return this.customerId;
	}

	public CustomerAccountEntryType getType() {
		return this.type;
	}

	public CustomerAccountEntryType.Direction getDirection() {
		return this.direction;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public BigDecimal getBalanceAfter() {
		return this.balanceAfter;
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
		CustomerAccountEntry entry = (CustomerAccountEntry) other;
		return this.id != null && this.id.equals(entry.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "CustomerAccountEntry[id=" + this.id + ", type=" + this.type + ", amount=" + this.amount + "]";
	}

}
