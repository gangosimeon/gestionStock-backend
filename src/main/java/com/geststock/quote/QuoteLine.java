package com.geststock.quote;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;

/**
 * Ligne de devis (table {@code quote_line}, V036) : produit du catalogue, référence et libellé
 * recopiés, prix unitaire retenu. Remplacée en bloc quand le devis est modifié.
 */
@Entity
@Table(name = "quote_line")
class QuoteLine {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "quote_id", nullable = false, updatable = false)
	private Quote quote;

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

	@Column(name = "quantity_per_beneficiary")
	private Integer quantityPerBeneficiary;

	@Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
	private BigDecimal unitPrice;

	@Column(name = "line_total", nullable = false, precision = 14, scale = 2)
	private BigDecimal lineTotal;

	protected QuoteLine() {
	}

	QuoteLine(Quote quote, int lineNumber, UUID productId, String productSku, String productName, int quantity,
			Integer quantityPerBeneficiary, BigDecimal unitPrice, BigDecimal lineTotal) {
		this.quote = quote;
		this.lineNumber = lineNumber;
		this.productId = productId;
		this.productSku = productSku;
		this.productName = productName;
		this.quantity = quantity;
		this.quantityPerBeneficiary = quantityPerBeneficiary;
		this.unitPrice = unitPrice;
		this.lineTotal = lineTotal;
	}

	UUID getId() {
		return this.id;
	}

	int getLineNumber() {
		return this.lineNumber;
	}

	UUID getProductId() {
		return this.productId;
	}

	String getProductSku() {
		return this.productSku;
	}

	String getProductName() {
		return this.productName;
	}

	int getQuantity() {
		return this.quantity;
	}

	/** Quantité pour un bénéficiaire, ou {@code null} : quantité saisie directement. */
	Integer getQuantityPerBeneficiary() {
		return this.quantityPerBeneficiary;
	}

	BigDecimal getUnitPrice() {
		return this.unitPrice;
	}

	BigDecimal getLineTotal() {
		return this.lineTotal;
	}

}
