package com.geststock.product;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.geststock.brand.Brand;
import com.geststock.category.Category;
import com.geststock.common.jpa.AuditableEntity;

/**
 * Produit du catalogue (table {@code product}, migration V011).
 *
 * <p><b>Aucune quantité en stock ici.</b> Le stock vit uniquement dans le module stock
 * (niveaux par dépôt, modifiés par des mouvements) ; le produit n'en est jamais une
 * seconde source.
 */
@Entity
@Table(name = "product")
public class Product extends AuditableEntity {

	@Column(name = "sku", nullable = false, length = 50)
	private String sku;

	@Column(name = "barcode", length = 14)
	private String barcode;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "description", length = 1000)
	private String description;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private Category category;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "brand_id")
	private Brand brand;

	/** Fournisseur habituel ; clé étrangère ajoutée avec le module fournisseur. */
	@Column(name = "default_supplier_id")
	private UUID defaultSupplierId;

	@Enumerated(EnumType.STRING)
	@Column(name = "unit", nullable = false, length = 20)
	private ProductUnit unit = ProductUnit.UNIT;

	@Column(name = "purchase_price", nullable = false, precision = 14, scale = 2)
	private BigDecimal purchasePrice = BigDecimal.ZERO;

	@Column(name = "retail_price", nullable = false, precision = 14, scale = 2)
	private BigDecimal retailPrice = BigDecimal.ZERO;

	@Column(name = "wholesale_price", nullable = false, precision = 14, scale = 2)
	private BigDecimal wholesalePrice = BigDecimal.ZERO;

	@Column(name = "alert_threshold", nullable = false)
	private int alertThreshold;

	@Column(name = "max_stock_level")
	private Integer maxStockLevel;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected Product() {
		// JPA
	}

	public Product(String sku, String name, Category category) {
		this.sku = Objects.requireNonNull(sku, "sku");
		this.name = Objects.requireNonNull(name, "name");
		this.category = Objects.requireNonNull(category, "category");
	}

	public String getSku() {
		return this.sku;
	}

	public String getBarcode() {
		return this.barcode;
	}

	public String getName() {
		return this.name;
	}

	public String getDescription() {
		return this.description;
	}

	public Category getCategory() {
		return this.category;
	}

	public Brand getBrand() {
		return this.brand;
	}

	public UUID getDefaultSupplierId() {
		return this.defaultSupplierId;
	}

	public ProductUnit getUnit() {
		return this.unit;
	}

	public BigDecimal getPurchasePrice() {
		return this.purchasePrice;
	}

	public BigDecimal getRetailPrice() {
		return this.retailPrice;
	}

	public BigDecimal getWholesalePrice() {
		return this.wholesalePrice;
	}

	public int getAlertThreshold() {
		return this.alertThreshold;
	}

	public Integer getMaxStockLevel() {
		return this.maxStockLevel;
	}

	public boolean isActive() {
		return this.active;
	}

	public void identify(String newSku, String newBarcode) {
		this.sku = Objects.requireNonNull(newSku, "sku");
		this.barcode = newBarcode;
	}

	public void describe(String newName, String newDescription, ProductUnit newUnit) {
		this.name = Objects.requireNonNull(newName, "name");
		this.description = newDescription;
		this.unit = Objects.requireNonNull(newUnit, "unit");
	}

	public void classify(Category newCategory, Brand newBrand, UUID newDefaultSupplierId) {
		this.category = Objects.requireNonNull(newCategory, "category");
		this.brand = newBrand;
		this.defaultSupplierId = newDefaultSupplierId;
	}

	public void reprice(BigDecimal newPurchasePrice, BigDecimal newRetailPrice, BigDecimal newWholesalePrice) {
		this.purchasePrice = Objects.requireNonNull(newPurchasePrice, "purchasePrice");
		this.retailPrice = Objects.requireNonNull(newRetailPrice, "retailPrice");
		this.wholesalePrice = Objects.requireNonNull(newWholesalePrice, "wholesalePrice");
	}

	/** Seuils de suivi du stock (alerte, excédent) : des paramètres, pas une quantité. */
	public void setStockThresholds(int newAlertThreshold, Integer newMaxStockLevel) {
		this.alertThreshold = newAlertThreshold;
		this.maxStockLevel = newMaxStockLevel;
	}

	public void activate() {
		this.active = true;
	}

	public void deactivate() {
		this.active = false;
	}

}
