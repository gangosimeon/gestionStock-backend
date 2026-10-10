package com.geststock.returns;

import java.math.BigDecimal;
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
 * Article retourné (table {@code customer_return_line}, V020), en ajout seul. Un article
 * revendable désigne la remise en stock qu'il a produite ; la base refuse une ligne sans
 * mouvement exactement correspondant.
 */
@Entity
@Immutable
@Table(name = "customer_return_line")
public class CustomerReturnLine {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "return_id", nullable = false)
	private UUID returnId;

	@Column(name = "line_number", nullable = false)
	private int lineNumber;

	@Column(name = "sale_line_id", nullable = false)
	private UUID saleLineId;

	@Column(name = "product_id", nullable = false)
	private UUID productId;

	@Column(name = "product_sku", nullable = false, length = 50)
	private String productSku;

	@Column(name = "product_name", nullable = false, length = 200)
	private String productName;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Column(name = "amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal amount;

	@Column(name = "unit_cost", nullable = false, precision = 14, scale = 2)
	private BigDecimal unitCost;

	@Enumerated(EnumType.STRING)
	@Column(name = "item_condition", nullable = false, length = 20)
	private ItemCondition condition;

	@Column(name = "stock_movement_id")
	private UUID stockMovementId;

	protected CustomerReturnLine() {
		// JPA
	}

	CustomerReturnLine(UUID returnId, int lineNumber, UUID saleLineId, UUID productId, String productSku,
			String productName, int quantity, BigDecimal amount, BigDecimal unitCost, ItemCondition condition,
			UUID stockMovementId) {
		this.returnId = Objects.requireNonNull(returnId, "returnId");
		this.lineNumber = lineNumber;
		this.saleLineId = Objects.requireNonNull(saleLineId, "saleLineId");
		this.productId = Objects.requireNonNull(productId, "productId");
		this.productSku = Objects.requireNonNull(productSku, "productSku");
		this.productName = Objects.requireNonNull(productName, "productName");
		this.quantity = quantity;
		this.amount = Objects.requireNonNull(amount, "amount");
		this.unitCost = Objects.requireNonNull(unitCost, "unitCost");
		this.condition = Objects.requireNonNull(condition, "condition");
		this.stockMovementId = stockMovementId;
	}

	public UUID getId() {
		return this.id;
	}

	public UUID getReturnId() {
		return this.returnId;
	}

	public int getLineNumber() {
		return this.lineNumber;
	}

	public UUID getSaleLineId() {
		return this.saleLineId;
	}

	public UUID getProductId() {
		return this.productId;
	}

	public String getProductSku() {
		return this.productSku;
	}

	public String getProductName() {
		return this.productName;
	}

	public int getQuantity() {
		return this.quantity;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public BigDecimal getUnitCost() {
		return this.unitCost;
	}

	public ItemCondition getCondition() {
		return this.condition;
	}

	public UUID getStockMovementId() {
		return this.stockMovementId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		CustomerReturnLine line = (CustomerReturnLine) other;
		return this.id != null && this.id.equals(line.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "CustomerReturnLine[product=" + this.productSku + ", quantity=" + this.quantity + "]";
	}

}
