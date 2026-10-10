package com.geststock.supplier;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/**
 * Fournisseur (table {@code supplier}, migration V014). Désactivé plutôt que supprimé : les
 * produits et les pièces d'achat le référencent.
 */
@Entity
@Table(name = "supplier")
public class Supplier extends AuditableEntity {

	@Column(name = "code", nullable = false, length = 20)
	private String code;

	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "contact_name", length = 150)
	private String contactName;

	@Column(name = "phone", length = 30)
	private String phone;

	@Column(name = "email", length = 254)
	private String email;

	@Column(name = "address", length = 400)
	private String address;

	@Column(name = "tax_id", length = 50)
	private String taxId;

	@Column(name = "delivery_lead_time_days", nullable = false)
	private int deliveryLeadTimeDays;

	@Column(name = "payment_terms_days", nullable = false)
	private int paymentTermsDays;

	@Column(name = "note", length = 1000)
	private String note;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected Supplier() {
		// JPA
	}

	public Supplier(String code, String name) {
		this.code = Objects.requireNonNull(code, "code");
		this.name = Objects.requireNonNull(name, "name");
	}

	public String getCode() {
		return this.code;
	}

	public String getName() {
		return this.name;
	}

	public String getContactName() {
		return this.contactName;
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

	public String getTaxId() {
		return this.taxId;
	}

	public int getDeliveryLeadTimeDays() {
		return this.deliveryLeadTimeDays;
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

	public void identify(String newCode, String newName) {
		this.code = Objects.requireNonNull(newCode, "code");
		this.name = Objects.requireNonNull(newName, "name");
	}

	public void changeContact(String newContactName, String newPhone, String newEmail, String newAddress) {
		this.contactName = newContactName;
		this.phone = newPhone;
		this.email = newEmail;
		this.address = newAddress;
	}

	public void changeTerms(String newTaxId, int newDeliveryLeadTimeDays, int newPaymentTermsDays) {
		this.taxId = newTaxId;
		this.deliveryLeadTimeDays = newDeliveryLeadTimeDays;
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

}
