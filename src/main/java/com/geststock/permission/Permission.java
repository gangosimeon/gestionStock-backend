package com.geststock.permission;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/**
 * Permission au format {@code domaine:action} (table {@code permission}, migrations V002/V003).
 * Le catalogue est livré par les migrations ; l'application le lit sans le modifier.
 */
@Entity
@Table(name = "permission")
public class Permission extends AuditableEntity {

	@Column(name = "code", nullable = false, length = 100, updatable = false)
	private String code;

	@Column(name = "description", nullable = false)
	private String description;

	protected Permission() {
		// JPA
	}

	public Permission(String code, String description) {
		this.code = Objects.requireNonNull(code, "code");
		this.description = Objects.requireNonNull(description, "description");
	}

	public String getCode() {
		return this.code;
	}

	public String getDescription() {
		return this.description;
	}

}
