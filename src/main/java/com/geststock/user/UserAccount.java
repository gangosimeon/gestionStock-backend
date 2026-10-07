package com.geststock.user;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Vue d'un compte pour l'authentification (lecture seule, hors transaction). Le haché du
 * mot de passe n'en sort que vers le fournisseur d'authentification ; il n'apparaît dans
 * aucune réponse HTTP.
 */
public record UserAccount(
		UUID id,
		String username,
		String passwordHash,
		boolean active,
		boolean mustChangePassword,
		Instant lockedUntil,
		Instant credentialsChangedAt,
		Set<String> roles,
		UUID defaultWarehouseId,
		Set<UUID> warehouseIds) {

	public boolean isLockedAt(Instant instant) {
		return this.lockedUntil != null && this.lockedUntil.isAfter(instant);
	}

	public boolean hasPassword() {
		return this.passwordHash != null;
	}

	@Override
	public String toString() {
		return "UserAccount[id=" + this.id + ", username=" + this.username + ", active=" + this.active + ", roles="
				+ this.roles + "]";
	}

}
