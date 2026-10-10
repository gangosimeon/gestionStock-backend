package com.geststock.audit;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import com.geststock.support.PostgresDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Journal d'audit : insertion JPA, colonnes jsonb, immuabilité garantie par la base. */
@PostgresDataJpaTest
class AuditLogRepositoryIT {

	@Autowired
	private AuditLogRepository repository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void storesAndReadsBackEntryWithJsonColumns() {
		Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
		UUID userId = UUID.randomUUID();
		AuditLog entry = AuditLog.builder(at, "UPDATE", "PRODUCT", AuditStatus.SUCCESS)
			.module("CATALOG").user(userId, "marie.k", "GESTIONNAIRE")
			.entity("p-1", "Monture Ray-Ban RB3025")
			.changes("[{\"field\": \"retailPrice\", \"newValue\": 35000, \"oldValue\": 32000}]")
			.states("{\"retailPrice\": 32000}", "{\"retailPrice\": 35000}")
			.client("192.168.1.20", "Mozilla/5.0")
			.traceId("trace-1234")
			.build();

		AuditLog saved = this.repository.saveAndFlush(entry);
		this.entityManager.clear();
		AuditLog reloaded = this.repository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded.getId().version()).isEqualTo(7);
		assertThat(reloaded.getCreatedAt()).isEqualTo(at);
		assertThat(reloaded.getUserId()).isEqualTo(userId);
		assertThat(reloaded.getStatus()).isEqualTo(AuditStatus.SUCCESS);
		assertThat(reloaded.getChanges()).contains("\"field\": \"retailPrice\"").contains("32000");
		assertThat(reloaded.getAfterState()).isEqualTo("{\"retailPrice\": 35000}");
		assertThat(reloaded.getMeta()).isNull();
	}

	@Test
	void jsonColumnsAreRealJsonbAndQueryable() {
		AuditLog saved = this.repository.saveAndFlush(
				AuditLog.builder(Instant.now(), "RECEIVE", "PURCHASE_ORDER", AuditStatus.SUCCESS).module("PURCHASE")
					.meta("{\"warehouse\": \"MAG-01\", \"lines\": 3}")
					.build());

		Number lines = (Number) this.entityManager
			.createNativeQuery("select (meta ->> 'lines')::int from audit_log where id = :id and meta ->> 'warehouse' = 'MAG-01'")
			.setParameter("id", saved.getId())
			.getSingleResult();
		assertThat(lines.intValue()).isEqualTo(3);
	}

	@Test
	void databaseRefusesAnyModification() {
		AuditLog saved = this.repository.saveAndFlush(
				AuditLog.builder(Instant.now(), "LOGIN", "SYSTEM", AuditStatus.FAILURE).module("AUTH").details("Mot de passe invalide").build());

		assertThatThrownBy(() -> this.entityManager
			.createNativeQuery("update audit_log set status = 'SUCCESS' where id = :id")
			.setParameter("id", saved.getId())
			.executeUpdate())
			.hasStackTraceContaining("ajout seul");
	}

	@Test
	void databaseRefusesDeletion() {
		AuditLog saved = this.repository.saveAndFlush(
				AuditLog.builder(Instant.now(), "EXPORT", "REPORT", AuditStatus.SUCCESS).module("SYSTEM").build());

		assertThatThrownBy(() -> this.entityManager
			.createNativeQuery("delete from audit_log where id = :id")
			.setParameter("id", saved.getId())
			.executeUpdate())
			.hasStackTraceContaining("ajout seul");
	}

}
