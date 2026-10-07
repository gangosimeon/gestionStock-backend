package com.geststock.security;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;

/**
 * Refresh token (table {@code refresh_token}, migration V008). Seul le haché SHA-256 est
 * conservé ; la valeur en clair n'existe que dans la réponse de connexion.
 */
@Entity
@Table(name = "refresh_token")
class RefreshToken {

	enum RevocationReason {
		ROTATED, LOGOUT, REUSE_DETECTED, CREDENTIALS_CHANGED, ACCOUNT_DISABLED
	}

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "token_hash", nullable = false, length = 64, updatable = false)
	private String tokenHash;

	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "revoked_reason", length = 30)
	private RevocationReason revokedReason;

	@Column(name = "replaced_by_id")
	private UUID replacedById;

	@Column(name = "user_agent")
	private String userAgent;

	@Column(name = "ip_address", length = 45)
	private String ipAddress;

	protected RefreshToken() {
		// JPA
	}

	RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant createdAt, Instant expiresAt, String userAgent,
			String ipAddress) {
		this.userId = Objects.requireNonNull(userId);
		this.tokenHash = Objects.requireNonNull(tokenHash);
		this.familyId = Objects.requireNonNull(familyId);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.expiresAt = Objects.requireNonNull(expiresAt);
		this.userAgent = userAgent;
		this.ipAddress = ipAddress;
	}

	UUID getId() {
		return this.id;
	}

	UUID getUserId() {
		return this.userId;
	}

	UUID getFamilyId() {
		return this.familyId;
	}

	Instant getExpiresAt() {
		return this.expiresAt;
	}

	RevocationReason getRevokedReason() {
		return this.revokedReason;
	}

	boolean isRevoked() {
		return this.revokedAt != null;
	}

	boolean isExpiredAt(Instant instant) {
		return !this.expiresAt.isAfter(instant);
	}

	void revoke(RevocationReason reason, Instant at) {
		if (this.revokedAt == null) {
			this.revokedAt = at;
			this.revokedReason = reason;
		}
	}

	void replaceBy(UUID successorId, Instant at) {
		revoke(RevocationReason.ROTATED, at);
		this.replacedById = successorId;
	}

}
