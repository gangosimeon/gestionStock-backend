package com.geststock.returns;

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

import com.geststock.cash.PaymentMethod;

/**
 * Retour client, qui est aussi l'avoir du client (table {@code customer_return}, V020). En
 * ajout seul : un retour enregistré ne se modifie ni ne se supprime. Créé uniquement par
 * {@link CustomerReturnService}.
 */
@Entity
@Immutable
@Table(name = "customer_return")
public class CustomerReturn {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "number", nullable = false, length = 30)
	private String number;

	@Column(name = "sale_id", nullable = false)
	private UUID saleId;

	@Column(name = "warehouse_id", nullable = false)
	private UUID warehouseId;

	@Column(name = "customer_id")
	private UUID customerId;

	@Enumerated(EnumType.STRING)
	@Column(name = "settlement", nullable = false, length = 20)
	private ReturnSettlement settlement;

	@Enumerated(EnumType.STRING)
	@Column(name = "refund_method", length = 20)
	private PaymentMethod refundMethod;

	@Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal totalAmount;

	@Column(name = "reason", nullable = false, length = 500)
	private String reason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	protected CustomerReturn() {
		// JPA
	}

	CustomerReturn(String number, UUID saleId, UUID warehouseId, UUID customerId, ReturnSettlement settlement,
			PaymentMethod refundMethod, BigDecimal totalAmount, String reason, Instant createdAt, UUID createdBy) {
		this.number = Objects.requireNonNull(number, "number");
		this.saleId = Objects.requireNonNull(saleId, "saleId");
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
		this.customerId = customerId;
		this.settlement = Objects.requireNonNull(settlement, "settlement");
		this.refundMethod = refundMethod;
		this.totalAmount = Objects.requireNonNull(totalAmount, "totalAmount");
		this.reason = Objects.requireNonNull(reason, "reason");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
	}

	public UUID getId() {
		return this.id;
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getSaleId() {
		return this.saleId;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public UUID getCustomerId() {
		return this.customerId;
	}

	public ReturnSettlement getSettlement() {
		return this.settlement;
	}

	public PaymentMethod getRefundMethod() {
		return this.refundMethod;
	}

	public BigDecimal getTotalAmount() {
		return this.totalAmount;
	}

	public String getReason() {
		return this.reason;
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
		CustomerReturn customerReturn = (CustomerReturn) other;
		return this.id != null && this.id.equals(customerReturn.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "CustomerReturn[id=" + this.id + ", number=" + this.number + "]";
	}

}
