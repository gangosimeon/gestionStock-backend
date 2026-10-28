package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.geststock.support.PostgresDataJpaTest;

/**
 * Schéma du flux d'événements d'intégration (conception D, plan d'intégration § 20.2 et § 22) :
 * {@code integration_outbox} (seq attribué à la lecture, write_id seule clé d'ordre) et
 * {@code integration_feed_state} (ligne unique). Les droits du rôle d'exécution d'une base
 * d'entreprise sont vérifiés par {@code MultiCompanyIT}, seul test connecté avec ce rôle.
 */
@PostgresDataJpaTest
class IntegrationSchemaIT {

	private static final String SINGLETON = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
	}

	@Test
	void outboxColumnsMatchTheDesign() {
		Map<String, Map<String, Object>> columns = columns("integration_outbox");
		assertThat(columns).containsOnlyKeys("id", "write_id", "seq", "type", "operation", "object_id",
				"object_version", "payload", "created_at");
		assertColumn(columns, "id", "uuid", "NO");
		assertColumn(columns, "write_id", "bigint", "NO");
		assertThat(columns.get("write_id")).containsEntry("is_identity", "YES").containsEntry("identity_generation", "ALWAYS");
		assertColumn(columns, "seq", "bigint", "YES");
		assertColumn(columns, "type", "character varying", "NO");
		assertThat(columns.get("type").get("character_maximum_length")).isNotNull();
		assertColumn(columns, "operation", "character varying", "NO");
		assertThat(columns.get("operation").get("character_maximum_length")).isNotNull();
		assertColumn(columns, "object_id", "uuid", "NO");
		assertColumn(columns, "object_version", "bigint", "NO");
		assertColumn(columns, "payload", "jsonb", "YES");
		assertColumn(columns, "created_at", "timestamp with time zone", "NO");
	}

	@Test
	void outboxKeysAreTheUuidIdAndUniqueWriteIdAndSeq() {
		assertThat(constraintColumns("pk_integration_outbox", "p")).containsExactly("id");
		assertThat(constraintColumns("uk_integration_outbox_write_id", "u")).containsExactly("write_id");
		assertThat(constraintColumns("uk_integration_outbox_seq", "u")).containsExactly("seq");
	}

	@Test
	void unsequencedEventsAreFoundThroughAPartialIndexOnWriteId() {
		String definition = this.jdbc.queryForObject(
				"select indexdef from pg_indexes where schemaname = current_schema() and indexname = 'ix_integration_outbox_unsequenced'",
				String.class);
		assertThat(definition).contains("(write_id)").contains("WHERE (seq IS NULL)");
	}

	@Test
	void writeIdGrowsStrictlyWithEachInsertion() {
		List<Long> writeIds = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			writeIds.add(insertEvent(null, "UPSERT"));
		}
		for (int i = 1; i < writeIds.size(); i++) {
			assertThat(writeIds.get(i)).isGreaterThan(writeIds.get(i - 1));
		}
	}

	@Test
	void manyEventsMayWaitWithoutSeqButASeqIsNeverReused() {
		insertEvent(null, "UPSERT");
		insertEvent(null, "UPSERT");
		insertEvent(1L, "UPSERT");
		assertThatThrownBy(() -> insertEvent(1L, "UPSERT")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void operationIsRestrictedToUpsertAndArchive() {
		insertEvent(null, "UPSERT");
		insertEvent(null, "ARCHIVE");
		assertThatThrownBy(() -> insertEvent(null, "DELETE")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void writeIdCannotBeChosenByTheWriter() {
		// GENERATED ALWAYS : PostgreSQL refuse toute valeur fournie (SQLSTATE 428C9).
		assertThatThrownBy(() -> this.jdbc.update("""
				insert into integration_outbox (write_id, type, operation, object_id, object_version)
				values (999999, 'CATEGORY', 'UPSERT', ?, 1)""", UUID.randomUUID()))
			.isInstanceOf(DataAccessException.class)
			.satisfies(ex -> assertThat(((DataAccessException) ex).getMostSpecificCause())
				.isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo("428C9")));
	}

	@Test
	void feedStateHoldsExactlyOneInitialRow() {
		List<Map<String, Object>> rows = this.jdbc.queryForList("select id, last_seq, purged_through_seq from integration_feed_state");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("id", UUID.fromString(SINGLETON))
			.containsEntry("last_seq", 0L)
			.containsEntry("purged_through_seq", 0L);
	}

	@Test
	void aSecondFeedStateRowIsRefused() {
		assertThatThrownBy(() -> this.jdbc.update("insert into integration_feed_state (id) values (?)", UUID.randomUUID()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theFeedStateRowCannotBeDuplicated() {
		assertThatThrownBy(() -> this.jdbc.update("insert into integration_feed_state (id) values (?)", UUID.fromString(SINGLETON)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void thePurgeMarkNeverPassesTheLastAssignedSeq() {
		assertThatThrownBy(() -> this.jdbc.update("update integration_feed_state set purged_through_seq = last_seq + 1"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insertEvent(Long seq, String operation) {
		return this.jdbc.queryForObject("""
				insert into integration_outbox (seq, type, operation, object_id, object_version)
				values (?, 'CATEGORY', ?, ?, 1) returning write_id""", Long.class, seq, operation, UUID.randomUUID());
	}

	private Map<String, Map<String, Object>> columns(String table) {
		Map<String, Map<String, Object>> columns = new java.util.LinkedHashMap<>();
		this.jdbc.queryForList("""
				select column_name, data_type, is_nullable, character_maximum_length, is_identity, identity_generation
				from information_schema.columns where table_schema = current_schema() and table_name = ?
				""", table).forEach(row -> columns.put((String) row.get("column_name"), row));
		return columns;
	}

	private static void assertColumn(Map<String, Map<String, Object>> columns, String name, String type, String nullable) {
		assertThat(columns.get(name)).as(name).containsEntry("data_type", type).containsEntry("is_nullable", nullable);
	}

	private List<String> constraintColumns(String name, String type) {
		return this.jdbc.queryForList("""
				select a.attname from pg_constraint con
				join pg_class c on c.oid = con.conrelid
				join pg_namespace n on n.oid = c.relnamespace
				join unnest(con.conkey) with ordinality k(attnum, ord) on true
				join pg_attribute a on a.attrelid = c.oid and a.attnum = k.attnum
				where n.nspname = current_schema() and con.conname = ? and con.contype = ?::"char"
				order by k.ord""", String.class, name, type);
	}

}
