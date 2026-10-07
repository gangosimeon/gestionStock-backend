package com.geststock.role;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;
import com.geststock.permission.Permission;

/**
 * Rôle et ses permissions (tables {@code role} et {@code role_permission}, migrations V002/V003).
 * Les rôles {@link #isBuiltIn() système} ne peuvent pas être supprimés.
 */
@Entity
@Table(name = "role")
public class Role extends AuditableEntity {

	@Column(name = "code", nullable = false, length = 50, updatable = false)
	private String code;

	@Column(name = "label", nullable = false, length = 100)
	private String label;

	@Column(name = "description")
	private String description;

	@Column(name = "built_in", nullable = false, updatable = false)
	private boolean builtIn;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "role_permission",
			joinColumns = @JoinColumn(name = "role_id"),
			inverseJoinColumns = @JoinColumn(name = "permission_id"))
	private Set<Permission> permissions = new HashSet<>();

	protected Role() {
		// JPA
	}

	public Role(String code, String label) {
		this.code = Objects.requireNonNull(code, "code");
		this.label = Objects.requireNonNull(label, "label");
	}

	public String getCode() {
		return this.code;
	}

	public String getLabel() {
		return this.label;
	}

	public String getDescription() {
		return this.description;
	}

	public boolean isBuiltIn() {
		return this.builtIn;
	}

	public Set<Permission> getPermissions() {
		return Collections.unmodifiableSet(this.permissions);
	}

	public void grant(Permission permission) {
		this.permissions.add(Objects.requireNonNull(permission, "permission"));
	}

	public void revoke(Permission permission) {
		this.permissions.remove(permission);
	}

	public void describe(String newLabel, String newDescription) {
		this.label = Objects.requireNonNull(newLabel, "label");
		this.description = newDescription;
	}

	public void replacePermissions(java.util.Collection<Permission> newPermissions) {
		this.permissions.clear();
		this.permissions.addAll(newPermissions);
	}

}
