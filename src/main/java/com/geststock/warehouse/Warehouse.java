package com.geststock.warehouse;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/**
 * Dépôt ou magasin (table {@code warehouse}, migrations V004 et V012). Désactivé plutôt que
 * supprimé. Un seul dépôt est « principal » ; il est toujours actif.
 *
 * <p>Entité publique car référencée par d'autres modules (utilisateurs, puis stock, ventes) ;
 * elle n'est modifiée que par le module {@code warehouse}.
 */
@Entity
@Table(name = "warehouse")
public class Warehouse extends AuditableEntity {

	@Column(name = "code", nullable = false, length = 20)
	private String code;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 20)
	private WarehouseType type;

	@Column(name = "address")
	private String address;

	@Column(name = "phone", length = 30)
	private String phone;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "main", nullable = false)
	private boolean main;

	protected Warehouse() {
		// JPA
	}

	public Warehouse(String code, String name, WarehouseType type) {
		this.code = Objects.requireNonNull(code, "code");
		this.name = Objects.requireNonNull(name, "name");
		this.type = Objects.requireNonNull(type, "type");
	}

	public String getCode() {
		return this.code;
	}

	public String getName() {
		return this.name;
	}

	public WarehouseType getType() {
		return this.type;
	}

	public String getAddress() {
		return this.address;
	}

	public String getPhone() {
		return this.phone;
	}

	public boolean isActive() {
		return this.active;
	}

	public boolean isMain() {
		return this.main;
	}

	public void update(String newCode, String newName, WarehouseType newType) {
		this.code = Objects.requireNonNull(newCode, "code");
		this.name = Objects.requireNonNull(newName, "name");
		this.type = Objects.requireNonNull(newType, "type");
	}

	public void rename(String newName) {
		this.name = Objects.requireNonNull(newName, "name");
	}

	public void changeContact(String newAddress, String newPhone) {
		this.address = newAddress;
		this.phone = newPhone;
	}

	/** @throws IllegalStateException pour le dépôt principal (règle vérifiée en amont par le service) */
	public void deactivate() {
		if (this.main) {
			throw new IllegalStateException("Le dépôt principal ne peut pas être désactivé.");
		}
		this.active = false;
	}

	void markMain() {
		if (!this.active) {
			throw new IllegalStateException("Un dépôt désactivé ne peut pas devenir principal.");
		}
		this.main = true;
	}

	void unmarkMain() {
		this.main = false;
	}

	public void activate() {
		this.active = true;
	}

}
