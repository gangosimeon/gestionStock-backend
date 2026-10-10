package com.geststock.sale;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.Hibernate;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * Ligne de vente (table {@code sale_line}, V017), en ajout seul. Prix et coût relus par le
 * serveur ; désigne la sortie de stock {@code SALE} qu'elle a produite (la base refuse une
 * ligne sans sortie exactement correspondante).
 */
@Entity
@Immutable
@Table(name = "sale_line")
public class SaleLine {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sale_id", nullable = false)
	private Sale sale;

	@Column(name = "line_number", nullable = false)
	private int lineNumber;

	@Column(name = "product_id", nullable = false)
	private UUID productId;

	@Column(name = "product_sku", nullable = false, length = 50)
	private String productSku;

	@Column(name = "product_name", nullable = false, length = 200)
	private String productName;

	@Column(name = "quantity", nullable = false)
	private int quantity;

	@Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
	private BigDecimal discountPercent;

	@Column(name = "discount_amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal discountAmount;

	@Column(name = "line_total", nullable = false, precision = 14, scale = 2)
	private BigDecimal lineTotal;

	@Column(name = "unit_cost", nullable = false, precision = 14, scale = 2)
	private BigDecimal unitCost;

	@Column(name = "cost_total", nullable = false, precision = 14, scale = 2)
	private BigDecimal costTotal;

	@Column(name = "stock_movement_id", nullable = false)
	private UUID stockMovementId;

	protected SaleLine() {
		// JPA
	}

	SaleLine(Sale sale, int lineNumber, UUID productId, String productSku, String productName, int quantity,
			BigDecimal unitPrice, BigDecimal discountPercent, SalePricing.LineAmounts amounts, UUID stockMovementId) {
		this.sale = Objects.requireNonNull(sale, "sale");
		this.lineNumber = lineNumber;
		this.productId = Objects.requireNonNull(productId, "productId");
		this.productSku = Objects.requireNonNull(productSku, "productSku");
		this.productName = Objects.requireNonNull(productName, "productName");
		this.quantity = quantity;
		this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
		this.discountPercent = Objects.requireNonNull(discountPercent, "discountPercent");
		this.discountAmount = amounts.discount();
		this.lineTotal = amounts.net();
		this.unitCost = amounts.unitCost();
		this.costTotal = amounts.costTotal();
		this.stockMovementId = Objects.requireNonNull(stockMovementId, "stockMovementId");
	}

	public UUID getId() {
		return this.id;
	}

	public Sale getSale() {
		return this.sale;
	}

	public int getLineNumber() {
		return this.lineNumber;
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

	public BigDecimal getUnitPrice() {
		return this.unitPrice;
	}

	public BigDecimal getDiscountPercent() {
		return this.discountPercent;
	}

	public BigDecimal getDiscountAmount() {
		return this.discountAmount;
	}

	public BigDecimal getLineTotal() {
		return this.lineTotal;
	}

	public BigDecimal getUnitCost() {
		return this.unitCost;
	}

	public BigDecimal getCostTotal() {
		return this.costTotal;
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
		SaleLine line = (SaleLine) other;
		return this.id != null && this.id.equals(line.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

	@Override
	public String toString() {
		return "SaleLine[id=" + this.id + ", product=" + this.productSku + ", quantity=" + this.quantity + "]";
	}

}
