package com.geststock.category;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/** Catégorie de produits (table {@code category}, migration V010). Désactivée plutôt que supprimée. */
@Entity
@Table(name = "category")
public class Category extends AuditableEntity {

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "description")
	private String description;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected Category() {
		// JPA
	}

	public Category(String name, String description) {
		this.name = Objects.requireNonNull(name, "name");
		this.description = description;
	}

	public String getName() {
		return this.name;
	}

	public String getDescription() {
		return this.description;
	}

	public boolean isActive() {
		return this.active;
	}

	public void update(String newName, String newDescription) {
		this.name = Objects.requireNonNull(newName, "name");
		this.description = newDescription;
	}

	public void activate() {
		this.active = true;
	}

	public void deactivate() {
		this.active = false;
	}

}
