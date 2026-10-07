package com.geststock.brand;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/** Marque (table {@code brand}, migration V010). Désactivée plutôt que supprimée. */
@Entity
@Table(name = "brand")
public class Brand extends AuditableEntity {

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected Brand() {
		// JPA
	}

	public Brand(String name) {
		this.name = Objects.requireNonNull(name, "name");
	}

	public String getName() {
		return this.name;
	}

	public boolean isActive() {
		return this.active;
	}

	public void rename(String newName) {
		this.name = Objects.requireNonNull(newName, "name");
	}

	public void activate() {
		this.active = true;
	}

	public void deactivate() {
		this.active = false;
	}

}
