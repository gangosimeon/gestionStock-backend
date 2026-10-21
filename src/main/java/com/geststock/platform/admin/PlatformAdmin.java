package com.geststock.platform.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * Compte Super Admin (base plateforme). {@link #toString()} ne révèle pas le haché du mot de passe.
 */
public record PlatformAdmin(UUID id, String username, String fullName, String email, String passwordHash,
		boolean active, boolean mustChangePassword, int failedLoginAttempts, Instant lockedUntil,
		Instant credentialsChangedAt, Instant lastLoginAt) {

	public boolean isLockedAt(Instant now) {
		return this.lockedUntil != null && this.lockedUntil.isAfter(now);
	}

	@Override
	public String toString() {
		return "PlatformAdmin[id=" + this.id + ", username=" + this.username + "]";
	}

}
