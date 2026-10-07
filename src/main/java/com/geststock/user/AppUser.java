package com.geststock.user;

import java.time.Instant;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;
import com.geststock.role.Role;
import com.geststock.warehouse.Warehouse;

/**
 * Compte utilisateur (table {@code app_user}, migration V005).
 *
 * <p>Sans {@link #getPasswordHash() mot de passe}, le compte ne peut pas se connecter.
 * Désactivé plutôt que supprimé, pour préserver l'historique.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends AuditableEntity {

	@Column(name = "username", nullable = false, length = 50)
	private String username;

	@Column(name = "first_name", length = 75)
	private String firstName;

	@Column(name = "last_name", nullable = false, length = 75)
	private String lastName;

	@Column(name = "email", length = 254)
	private String email;

	@Column(name = "phone", length = 30)
	private String phone;

	@Column(name = "password_hash")
	private String passwordHash;

	@Column(name = "must_change_password", nullable = false)
	private boolean mustChangePassword = true;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts;

	@Column(name = "locked_until")
	private Instant lockedUntil;

	@Column(name = "last_login_at")
	private Instant lastLoginAt;

	@Column(name = "credentials_changed_at")
	private Instant credentialsChangedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "default_warehouse_id")
	private Warehouse defaultWarehouse;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "user_role",
			joinColumns = @JoinColumn(name = "user_id"),
			inverseJoinColumns = @JoinColumn(name = "role_id"))
	private Set<Role> roles = new HashSet<>();

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "user_warehouse",
			joinColumns = @JoinColumn(name = "user_id"),
			inverseJoinColumns = @JoinColumn(name = "warehouse_id"))
	private Set<Warehouse> warehouses = new HashSet<>();

	protected AppUser() {
		// JPA
	}

	public AppUser(String username, String firstName, String lastName) {
		this.username = Objects.requireNonNull(username, "username");
		this.firstName = firstName;
		this.lastName = Objects.requireNonNull(lastName, "lastName");
	}

	public String getUsername() {
		return this.username;
	}

	public String getFirstName() {
		return this.firstName;
	}

	public String getLastName() {
		return this.lastName;
	}

	/** Nom complet affiché : « Prénom Nom », ou le nom seul. */
	public String getFullName() {
		return this.firstName != null ? this.firstName + " " + this.lastName : this.lastName;
	}

	public boolean hasPassword() {
		return this.passwordHash != null;
	}

	public String getEmail() {
		return this.email;
	}

	public String getPhone() {
		return this.phone;
	}

	public String getPasswordHash() {
		return this.passwordHash;
	}

	public boolean isMustChangePassword() {
		return this.mustChangePassword;
	}

	public boolean isActive() {
		return this.active;
	}

	public int getFailedLoginAttempts() {
		return this.failedLoginAttempts;
	}

	public Instant getLockedUntil() {
		return this.lockedUntil;
	}

	public Instant getLastLoginAt() {
		return this.lastLoginAt;
	}

	/** Les jetons d'accès émis avant cet instant sont refusés. */
	public Instant getCredentialsChangedAt() {
		return this.credentialsChangedAt;
	}

	public Warehouse getDefaultWarehouse() {
		return this.defaultWarehouse;
	}

	public Set<Role> getRoles() {
		return Collections.unmodifiableSet(this.roles);
	}

	public Set<Warehouse> getWarehouses() {
		return Collections.unmodifiableSet(this.warehouses);
	}

	public void rename(String newUsername, String newFirstName, String newLastName) {
		this.username = Objects.requireNonNull(newUsername, "username");
		this.firstName = newFirstName;
		this.lastName = Objects.requireNonNull(newLastName, "lastName");
	}

	public void changeContact(String newEmail, String newPhone) {
		this.email = newEmail;
		this.phone = newPhone;
	}

	public void replaceRoles(java.util.Collection<Role> newRoles) {
		this.roles.clear();
		this.roles.addAll(newRoles);
	}

	public void replaceWarehouses(java.util.Collection<Warehouse> newWarehouses) {
		this.warehouses.clear();
		this.warehouses.addAll(newWarehouses);
	}

	public boolean hasRole(String roleCode) {
		return this.roles.stream().anyMatch(role -> role.getCode().equals(roleCode));
	}

	/**
	 * Enregistre un mot de passe déjà haché ({@code {bcrypt}...}) et invalide les jetons
	 * d'accès émis auparavant.
	 */
	public void changePasswordHash(String newPasswordHash, boolean temporary, Instant changedAt) {
		this.passwordHash = Objects.requireNonNull(newPasswordHash, "passwordHash");
		this.mustChangePassword = temporary;
		this.credentialsChangedAt = Objects.requireNonNull(changedAt, "changedAt");
		this.failedLoginAttempts = 0;
		this.lockedUntil = null;
	}

	public void assignRole(Role role) {
		this.roles.add(Objects.requireNonNull(role, "role"));
	}

	public void removeRole(Role role) {
		this.roles.remove(role);
	}

	public void grantWarehouse(Warehouse warehouse) {
		this.warehouses.add(Objects.requireNonNull(warehouse, "warehouse"));
	}

	public void revokeWarehouse(Warehouse warehouse) {
		this.warehouses.remove(warehouse);
	}

	public void setDefaultWarehouse(Warehouse warehouse) {
		this.defaultWarehouse = warehouse;
	}

	public void recordSuccessfulLogin(Instant at) {
		this.lastLoginAt = at;
		this.failedLoginAttempts = 0;
		this.lockedUntil = null;
	}

	public void recordFailedLogin(int maxAttempts, Instant lockUntilIfExceeded) {
		this.failedLoginAttempts++;
		if (this.failedLoginAttempts >= maxAttempts) {
			this.lockedUntil = lockUntilIfExceeded;
		}
	}

	public boolean isLockedAt(Instant instant) {
		return this.lockedUntil != null && this.lockedUntil.isAfter(instant);
	}

	public void deactivate() {
		this.active = false;
	}

	public void activate() {
		this.active = true;
	}

}
