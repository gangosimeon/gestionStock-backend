package com.geststock.warehouse;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.geststock.support.PostgresDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Accès JPA aux dépôts : mapping, UUID v7, audit technique, verrou optimiste, contraintes. */
@PostgresDataJpaTest
class WarehouseRepositoryIT {

	@Autowired
	private WarehouseRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void mainWarehouseIsProvidedByMigration() {
		Warehouse main = this.repository.findByCode("MAG-01").orElseThrow();

		assertThat(main.getName()).isEqualTo("Magasin principal");
		assertThat(main.getType()).isEqualTo(WarehouseType.STORE);
		assertThat(main.isActive()).isTrue();
		assertThat(main.isMain()).as("V012 : dépôt principal").isTrue();
		assertThat(this.repository.findByMainTrue()).contains(main);
	}

	@Test
	void databaseAllowsOnlyOneMainWarehouse() {
		Warehouse other = this.repository.saveAndFlush(new Warehouse("TEST-M1", "Second principal", WarehouseType.STORE));
		other.markMain();

		assertThatThrownBy(() -> this.repository.saveAndFlush(other))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(constraintName(ex)).isEqualTo("uk_warehouse_main"));
	}

	@Test
	void mainWarehouseCannotBeDeactivated() {
		Warehouse main = this.repository.findByMainTrue().orElseThrow();
		assertThatThrownBy(main::deactivate).isInstanceOf(IllegalStateException.class);

		assertThatThrownBy(() -> this.entityManager
			.createNativeQuery("update warehouse set active = false where main")
			.executeUpdate())
			.satisfies(ex -> assertThat(constraintName(ex)).isEqualTo("ck_warehouse_main_active"));
	}

	@Test
	void persistsWithTimeOrderedUuidAndTechnicalAuditColumns() {
		Instant before = Instant.now();
		Warehouse saved = this.repository.saveAndFlush(new Warehouse("TEST-01", "Dépôt de test", WarehouseType.WAREHOUSE));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getId().version()).as("UUID v7").isEqualTo(7);
		assertThat(saved.getCreatedAt()).isAfterOrEqualTo(before.minusSeconds(1));
		assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
		assertThat(saved.getCreatedBy()).as("aucun utilisateur authentifié").isNull();
	}

	@Test
	void incrementsVersionOnUpdate() {
		Warehouse saved = this.repository.saveAndFlush(new Warehouse("TEST-02", "Avant", WarehouseType.STORE));
		long initialVersion = saved.getVersion();

		saved.rename("Après");
		saved.deactivate();
		this.repository.saveAndFlush(saved);
		this.entityManager.clear();

		Warehouse reloaded = this.repository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getName()).isEqualTo("Après");
		assertThat(reloaded.isActive()).isFalse();
		assertThat(reloaded.getVersion()).isEqualTo(initialVersion + 1);
	}

	@Test
	void rejectsDuplicateNameIgnoringCaseWithNamedConstraint() {
		assertThatThrownBy(() -> this.repository.saveAndFlush(
				new Warehouse("TEST-03", "MAGASIN PRINCIPAL", WarehouseType.STORE)))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(constraintName(ex)).isEqualTo("uk_warehouse_name"));
	}

	@Test
	void rejectsInvalidCodeThroughCheckConstraint() {
		assertThatThrownBy(() -> this.repository.saveAndFlush(
				new Warehouse("bad code", "Code invalide", WarehouseType.STORE)))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(constraintName(ex)).isEqualTo("ck_warehouse_code_format"));
	}

	/** Même extraction que {@code GlobalExceptionHandler} : nom de contrainte porté par Hibernate. */
	static String constraintName(Throwable ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
			cause = cause.getCause();
		}
		return null;
	}

}
