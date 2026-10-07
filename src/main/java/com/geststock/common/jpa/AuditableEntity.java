package com.geststock.common.jpa;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Entité modifiable portant les colonnes d'audit technique, renseignées automatiquement par
 * Spring Data JPA (voir {@code JpaConfig}). Le journal d'audit métier (avant/après, action)
 * est distinct : module {@code audit}.
 *
 * <p>Colonnes attendues dans chaque table concernée : {@code created_at}, {@code created_by},
 * {@code updated_at}, {@code updated_by}.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity extends BaseEntity {

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@CreatedBy
	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@LastModifiedBy
	@Column(name = "updated_by")
	private UUID updatedBy;

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public UUID getCreatedBy() {
		return this.createdBy;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public UUID getUpdatedBy() {
		return this.updatedBy;
	}

}
