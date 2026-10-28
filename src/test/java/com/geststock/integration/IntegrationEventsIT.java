package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import com.geststock.category.Category;
import com.geststock.support.PostgresTestConfiguration;

import tools.jackson.databind.json.JsonMapper;

/**
 * Point d'entrée unique de publication ({@link IntegrationEvents}) sur un vrai PostgreSQL : écriture
 * dans la transaction de l'appelant (règle R1), aucune écriture hors transaction, après annulation,
 * interrupteur coupé ou charge trop lourde. Les transactions sont réelles (validées ou annulées) ;
 * les catégories et événements créés sont supprimés après chaque test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class IntegrationEventsIT {

	@Autowired
	private IntegrationEvents events;

	@Autowired
	private OutboxWriter writer;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate jdbc;

	private TransactionTemplate tx;

	private final List<UUID> touched = new ArrayList<>();

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		this.tx = new TransactionTemplate(this.transactionManager);
	}

	@AfterEach
	void cleanUp() {
		this.tx.executeWithoutResult(status -> {
			for (UUID id : this.touched) {
				this.jdbc.update("delete from integration_outbox where object_id = ?", id);
				this.jdbc.update("delete from category where id = ?", id);
			}
		});
	}

	@Test
	void aCreationIsPublishedInTheSameTransactionWithThePersistedVersion() {
		UUID id = this.tx.execute(status -> {
			Category category = newCategory();
			this.events.publish(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, category,
					Map.of("name", category.getName()));
			// Visible dans la transaction de l'appelant, avant sa validation.
			assertThat(eventsOf(category.getId())).hasSize(1);
			return category.getId();
		});

		Map<String, Object> event = singleEvent(id);
		assertThat(event.get("seq")).isNull();
		assertThat(event).containsEntry("type", "CATEGORY").containsEntry("operation", "UPSERT");
		assertThat(event.get("object_version")).isEqualTo(persistedVersion(id));
		assertThat(event.get("payload").toString()).contains("\"name\"");
	}

	@Test
	void aModificationIsPublishedWithTheVersionWrittenByTheFlush() {
		UUID id = this.tx.execute(status -> {
			Category category = newCategory();
			this.events.publish(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, category, null);
			return category.getId();
		});
		this.tx.executeWithoutResult(status -> {
			Category category = this.entityManager.find(Category.class, id);
			category.update(category.getName() + " modifiée", null);
			this.events.publish(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, category, null);
		});

		List<Map<String, Object>> published = eventsOf(id);
		assertThat(published).hasSize(2);
		long persisted = persistedVersion(id);
		assertThat(persisted).isGreaterThan(0L);
		assertThat(published.get(1).get("object_version")).isEqualTo(persisted);
		assertThat((Long) published.get(1).get("object_version")).isGreaterThan((Long) published.get(0).get("object_version"));
	}

	@Test
	void aRolledBackBusinessTransactionLeavesNoEvent() {
		UUID id = this.tx.execute(status -> {
			Category category = newCategory();
			this.events.publish(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, category, null);
			status.setRollbackOnly();
			return category.getId();
		});
		assertThat(eventsOf(id)).isEmpty();
		assertThat(this.jdbc.queryForObject("select count(*) from category where id = ?", Integer.class, id)).isZero();
	}

	@Test
	void publishingOutsideATransactionFailsAndWritesNothing() {
		UUID objectId = track(UUID.randomUUID());
		assertThatThrownBy(() -> this.events.publish(IntegrationEventType.AVAILABILITY, objectId))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(eventsOf(objectId)).isEmpty();
	}

	@Test
	void aDisabledSwitchWritesNothing() {
		IntegrationEvents disabled = new IntegrationEvents(
				new IntegrationProperties(new IntegrationProperties.Publish(false, DataSize.ofKilobytes(16))), this.writer,
				this.jsonMapper, this.entityManager);
		UUID id = this.tx.execute(status -> {
			Category category = newCategory();
			disabled.publish(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, category, null);
			disabled.publish(IntegrationEventType.AVAILABILITY, category.getId());
			return category.getId();
		});
		assertThat(eventsOf(id)).isEmpty();
	}

	@Test
	void anOversizedPayloadIsRefusedAndNothingIsWritten() {
		List<UUID> ids = new ArrayList<>();
		assertThatThrownBy(() -> this.tx.executeWithoutResult(status -> {
			Category category = newCategory();
			ids.add(category.getId());
			this.events.publish(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, category,
					Map.of("blob", "x".repeat(17 * 1024)));
		})).isInstanceOf(IntegrationPayloadTooLargeException.class);
		assertThat(eventsOf(ids.get(0))).isEmpty();
	}

	@Test
	void theEventIdIsAUuidVersion7() {
		UUID id = this.tx.execute(status -> {
			Category category = newCategory();
			this.events.publish(IntegrationEventType.CATEGORY, IntegrationOperation.UPSERT, category, null);
			return category.getId();
		});
		assertThat(((UUID) singleEvent(id).get("id")).version()).isEqualTo(7);
	}

	@Test
	void aThinEventCarriesVersionZeroAndNoPayload() {
		UUID productId = track(UUID.randomUUID());
		this.tx.executeWithoutResult(status -> this.events.publish(IntegrationEventType.AVAILABILITY, productId));
		Map<String, Object> event = singleEvent(productId);
		assertThat(event).containsEntry("type", "AVAILABILITY").containsEntry("operation", "UPSERT")
			.containsEntry("object_version", 0L);
		assertThat(event.get("payload")).isNull();
		assertThat(event.get("seq")).isNull();
	}

	@Test
	void eachVariantOnlyAcceptsItsOwnKindOfEvent() {
		UUID productId = track(UUID.randomUUID());
		this.tx.executeWithoutResult(status -> {
			Category category = newCategory();
			assertThatThrownBy(() -> this.events.publish(IntegrationEventType.AVAILABILITY, IntegrationOperation.UPSERT,
					category, null)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> this.events.publish(IntegrationEventType.CATEGORY, productId))
				.isInstanceOf(IllegalArgumentException.class);
			status.setRollbackOnly();
		});
	}

	private Category newCategory() {
		Category category = new Category("Intégration " + UUID.randomUUID(), null);
		this.entityManager.persist(category);
		track(category.getId());
		return category;
	}

	private UUID track(UUID id) {
		this.touched.add(id);
		return id;
	}

	private List<Map<String, Object>> eventsOf(UUID objectId) {
		return this.jdbc.queryForList("""
				select id, seq, type, operation, object_id, object_version, payload
				from integration_outbox where object_id = ? order by write_id""", objectId);
	}

	private Map<String, Object> singleEvent(UUID objectId) {
		List<Map<String, Object>> published = eventsOf(objectId);
		assertThat(published).hasSize(1);
		return published.get(0);
	}

	private long persistedVersion(UUID categoryId) {
		return this.jdbc.queryForObject("select version from category where id = ?", Long.class, categoryId);
	}

}
