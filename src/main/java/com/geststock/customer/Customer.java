package com.geststock.customer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/**
 * Client (table {@code customer}, migration V016). Désactivé plutôt que supprimé : ventes,
 * paiements et compte client le référencent. Sa dette ne vit pas ici mais dans son compte
 * ({@link CustomerAccountEntry}).
 */
@Entity
@Table(name = "customer")
public class Customer extends AuditableEntity {

	@Column(name = "code", nullable = false, length = 20)
	private String code;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 20)
	private CustomerType type = CustomerType.INDIVIDUAL;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "phone", length = 30)
	private String phone;

	@Column(name = "email", length = 254)
	private String email;

	@Column(name = "address", length = 400)
	private String address;

	@Column(name = "city", length = 100)
	private String city;

	@Column(name = "tax_id", length = 50)
	private String taxId;

	@Enumerated(EnumType.STRING)
	@Column(name = "price_category", nullable = false, length = 20)
	private PriceCategory priceCategory = PriceCategory.RETAIL;

	@Column(name = "credit_limit", nullable = false, precision = 14, scale = 2)
	private BigDecimal creditLimit = BigDecimal.ZERO.setScale(2);

	@Column(name = "payment_terms_days", nullable = false)
	private int paymentTermsDays;

	@Column(name = "note", length = 1000)
	private String note;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected Customer() {
		// JPA
	}

	public Customer(String code, CustomerType type, String name) {
		this.code = Objects.requireNonNull(code, "code");
		this.type = Objects.requireNonNull(type, "type");
		this.name = Objects.requireNonNull(name, "name");
	}

	public void identify(String newCode, CustomerType newType, String newName, String newTaxId) {
		this.code = Objects.requireNonNull(newCode, "code");
		this.type = Objects.requireNonNull(newType, "type");
		this.name = Objects.requireNonNull(newName, "name");
		this.taxId = newTaxId;
	}

	public void changeContact(String newPhone, String newEmail, String newAddress, String newCity) {
		this.phone = newPhone;
		this.email = newEmail;
		this.address = newAddress;
		this.city = newCity;
	}

	/** Conditions commerciales ; le plafond n'est modifiable qu'avec {@code customer:credit} (service). */
	public void changeTerms(PriceCategory newPriceCategory, BigDecimal newCreditLimit, int newPaymentTermsDays) {
		this.priceCategory = Objects.requireNonNull(newPriceCategory, "priceCategory");
		if (newCreditLimit.signum() < 0) {
			throw new IllegalArgumentException("Plafond de crédit négatif.");
		}
		this.creditLimit = newCreditLimit.setScale(2, RoundingMode.UNNECESSARY);
		this.paymentTermsDays = newPaymentTermsDays;
	}

	public void annotate(String newNote) {
		this.note = newNote;
	}

	public void activate() {
		this.active = true;
	}

	public void deactivate() {
		this.active = false;
	}

	public String getCode() {
		return this.code;
	}

	public CustomerType getType() {
		return this.type;
	}

	public String getName() {
		return this.name;
	}

	public String getPhone() {
		return this.phone;
	}

	public String getEmail() {
		return this.email;
	}

	public String getAddress() {
		return this.address;
	}

	public String getCity() {
		return this.city;
	}

	public String getTaxId() {
		return this.taxId;
	}

	public PriceCategory getPriceCategory() {
		return this.priceCategory;
	}

	public BigDecimal getCreditLimit() {
		return this.creditLimit;
	}

	public int getPaymentTermsDays() {
		return this.paymentTermsDays;
	}

	public String getNote() {
		return this.note;
	}

	public boolean isActive() {
		return this.active;
	}

}
