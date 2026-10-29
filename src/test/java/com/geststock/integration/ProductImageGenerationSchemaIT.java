package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresDataJpaTest;

/**
 * Table {@code product_image_generation} (P7, plan § 20.3) : compteur durable de génération de l'image de chaque
 * produit, jamais supprimé avec l'image, rempli par la migration pour les images existantes (Q6). Le
 * remplissage est vérifié sur un schéma isolé migré jusqu'à la version précédente, puis jusqu'à la dernière ;
 * le schéma principal n'est pas touché. Le script U est vérifié par {@code MigrationRollbackIT}.
 */
@PostgresDataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProductImageGenerationSchemaIT {

	static final String SCRATCH = "image_generation_check";

	private static final String SHA_A = "a".repeat(64);

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		this.jdbc.execute("drop schema if exists " + SCRATCH + " cascade");
	}

	@AfterEach
	void dropScratch() {
		this.jdbc.execute("drop schema if exists " + SCRATCH + " cascade");
	}

	@Test
	void columnsMatchTheDesignWithoutVersionColumn() {
		List<Map<String, Object>> columns = this.jdbc.queryForList("""
				select column_name, data_type, is_nullable, character_maximum_length
				from information_schema.columns
				where table_schema = current_schema() and table_name = 'product_image_generation'
				order by ordinal_position""");
		assertThat(columns).extracting(c -> c.get("column_name"))
			.containsExactly("product_id", "generation", "sha256", "updated_at");
		assertThat(columns).extracting(c -> c.get("data_type"))
			.containsExactly("uuid", "bigint", "character varying", "timestamp with time zone");
		assertThat(columns).extracting(c -> c.get("is_nullable")).containsExactly("NO", "NO", "YES", "NO");
		assertThat(columns.get(2).get("character_maximum_length")).isEqualTo(64);
	}

	@Test
	void theProductIsTheKeyAndItsDeletionCascades() {
		assertThat(this.jdbc.queryForList("""
				select a.attname from pg_constraint con
				join pg_attribute a on a.attrelid = con.conrelid and a.attnum = any(con.conkey)
				where con.conname = 'pk_product_image_generation' and con.contype = 'p'""", String.class))
			.containsExactly("product_id");
		assertThat(this.jdbc.queryForObject("""
				select confdeltype from pg_constraint
				where conname = 'fk_product_image_generation_product' and contype = 'f'""", String.class))
			.as("ON DELETE CASCADE").isEqualTo("c");
		assertThat(this.jdbc.queryForObject("select obj_description('product_image_generation'::regclass)", String.class))
			.isNotBlank();
	}

	@Test
	void generationIsPositiveAndTheFingerprintIsAHexSha256OrNull() {
		UUID product = insertProduct(this.jdbc, "");
		try {
			assertThatThrownBy(() -> this.jdbc.update(
					"insert into product_image_generation (product_id, generation, sha256) values (?, 0, null)", product))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_product_image_generation");
			assertThatThrownBy(() -> this.jdbc.update(
					"insert into product_image_generation (product_id, generation, sha256) values (?, 1, 'XYZ')", product))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_product_image_generation");
			this.jdbc.update("insert into product_image_generation (product_id, generation, sha256) values (?, 1, null)",
					product);
			assertThat(this.jdbc.queryForObject(
					"select updated_at is not null from product_image_generation where product_id = ?", Boolean.class,
					product)).isTrue();

			this.jdbc.update("delete from product where id = ?", product);
			assertThat(this.jdbc.queryForObject("select count(*) from product_image_generation where product_id = ?",
					Integer.class, product)).as("supprimée avec le produit").isZero();
		}
		finally {
			this.jdbc.update("delete from product where id = ?", product);
		}
	}

	@Test
	void theMigrationCreatesGenerationOneForEachExistingImageAndNothingElse() {
		migrateScratchTo("47");
		UUID withImage = insertProduct(this.jdbc, SCRATCH + ".");
		UUID withoutImage = insertProduct(this.jdbc, SCRATCH + ".");
		insertImage(this.jdbc, SCRATCH + ".", withImage, SHA_A);

		migrateScratchTo(null);

		List<Map<String, Object>> rows = this.jdbc
			.queryForList("select product_id, generation, sha256 from " + SCRATCH + ".product_image_generation");
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("product_id", withImage).containsEntry("generation", 1L)
			.containsEntry("sha256", SHA_A);
		assertThat(rows).extracting(r -> r.get("product_id")).doesNotContain(withoutImage);
	}

	// --- Outils (aussi utilisés par ProductImageEventsIT) --------------------------------------------------

	private void migrateScratchTo(String target) {
		migrate(this.dataSource, target);
	}

	/** Migre le schéma isolé jusqu'à {@code target} ({@code null} : dernière version). */
	static void migrate(DataSource dataSource, String target) {
		var configuration = Flyway.configure()
			.dataSource(dataSource)
			.schemas(SCRATCH)
			.createSchemas(true)
			.locations("classpath:db/migration")
			.cleanDisabled(true);
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	/** Produit minimal ; {@code prefix} : {@code ""} (schéma courant) ou {@code "<schéma>."}. */
	static UUID insertProduct(JdbcTemplate jdbc, String prefix) {
		return jdbc.queryForObject("insert into " + prefix + "product (sku, name, category_id) select ?, 'Photo', id from "
				+ prefix + "category order by name limit 1 returning id", UUID.class,
				"GEN-" + UUID.randomUUID().toString().substring(0, 8));
	}

	/** Image minimale conforme aux contraintes de {@code product_image}. */
	static void insertImage(JdbcTemplate jdbc, String prefix, UUID product, String sha256) {
		jdbc.update("insert into " + prefix + "product_image (product_id, content_type, data, size_bytes, width, height, sha256) "
				+ "values (?, 'image/jpeg', ?, 3, 1, 1, ?)", product, new byte[] { 1, 2, 3 }, sha256);
	}

}
