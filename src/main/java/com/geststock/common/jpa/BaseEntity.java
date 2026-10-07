package com.geststock.common.jpa;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import org.hibernate.Hibernate;
import org.hibernate.annotations.UuidGenerator;

/**
 * Racine de toutes les entités modifiables : identifiant UUID v7 généré par l'application
 * (ordonné dans le temps, donc favorable aux index B-tree) et version pour le verrouillage
 * optimiste (conflit → 409 {@code CONCURRENT_MODIFICATION}). Voir {@code docs/persistence.md}.
 *
 * <p>Égalité basée sur l'identifiant : deux entités transitoires (non persistées) ne sont
 * jamais égales. Le {@code hashCode} est constant par classe pour rester stable avant et
 * après la persistance.
 */
@MappedSuperclass
public abstract class BaseEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	public UUID getId() {
		return this.id;
	}

	public long getVersion() {
		return this.version;
	}

	@Override
	public final boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		BaseEntity entity = (BaseEntity) other;
		return this.id != null && this.id.equals(entity.id);
	}

	@Override
	public final int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

	@Override
	public String toString() {
		return Hibernate.getClass(this).getSimpleName() + "[id=" + this.id + "]";
	}

}
