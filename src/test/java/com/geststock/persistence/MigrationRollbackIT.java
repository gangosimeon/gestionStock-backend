package com.geststock.persistence;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vérifie la procédure de rollback documentée ({@code docs/persistence.md} §8) : sur un
 * schéma isolé, toutes les migrations sont appliquées, puis annulées dans l'ordre inverse
 * par les scripts {@code db/rollback/U*.sql}, puis ré-appliquées. Le schéma principal
 * n'est jamais touché.
 */
@PostgresDataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MigrationRollbackIT {

	private static final String SCHEMA = "rollback_check";

	@Autowired
	private DataSource dataSource;

	@AfterEach
	void dropScratchSchema() {
		new JdbcTemplate(this.dataSource).execute("drop schema if exists " + SCHEMA + " cascade");
	}

	@Test
	void everyMigrationCanBeRolledBackAndReappliedInOrder() throws Exception {
		JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
		jdbc.execute("drop schema if exists " + SCHEMA + " cascade");

		MigrateResult first = flyway().migrate();
		int expected = MigrationFiles.versions().size();
		assertThat(first.migrationsExecuted).isEqualTo(expected);
		assertThat(tablesInScratchSchema(jdbc)).contains("app_user", "audit_log", "warehouse");

		List<Resource> rollbacks = rollbackScriptsNewestFirst();
		assertThat(rollbacks).hasSize(expected);
		try (Connection connection = this.dataSource.getConnection()) {
			try (Statement statement = connection.createStatement()) {
				statement.execute("set search_path to " + SCHEMA);
			}
			for (Resource script : rollbacks) {
				ScriptUtils.executeSqlScript(connection, script);
			}
			try (Statement statement = connection.createStatement()) {
				statement.execute("reset search_path");
			}
		}

		assertThat(tablesInScratchSchema(jdbc)).as("seule l'historique Flyway subsiste")
			.containsExactly("flyway_schema_history");
		assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".flyway_schema_history where version is not null",
				Integer.class)).isZero();
		assertThat(jdbc.queryForObject("""
				select count(*) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
				where n.nspname = ?""", Integer.class, SCHEMA)).as("fonctions restantes").isZero();

		MigrateResult second = flyway().migrate();
		assertThat(second.migrationsExecuted).as("ré-application après rollback complet").isEqualTo(expected);
	}

	private Flyway flyway() {
		return Flyway.configure()
			.dataSource(this.dataSource)
			.schemas(SCHEMA)
			.createSchemas(true)
			.locations("classpath:db/migration")
			.cleanDisabled(true)
			.load();
	}

	private static List<Resource> rollbackScriptsNewestFirst() throws Exception {
		Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath:db/rollback/U*.sql");
		return Arrays.stream(scripts).sorted(Comparator.comparing(Resource::getFilename).reversed()).toList();
	}

	private static List<String> tablesInScratchSchema(JdbcTemplate jdbc) {
		return jdbc.queryForList("select tablename from pg_tables where schemaname = ? order by 1", String.class,
				SCHEMA);
	}

}
