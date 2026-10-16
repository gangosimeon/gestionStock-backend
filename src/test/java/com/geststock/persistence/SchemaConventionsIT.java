package com.geststock.persistence;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.geststock.support.PostgresDataJpaTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le schéma produit par les migrations respecte les conventions de
 * {@code docs/persistence.md}. Ce test s'applique automatiquement à toute future table :
 * une migration qui oublie un index de clé étrangère ou nomme mal une contrainte casse le build.
 */
@PostgresDataJpaTest
class SchemaConventionsIT {

	private static final String TABLES_SQL = """
			select c.relname
			from pg_class c join pg_namespace n on n.oid = c.relnamespace
			where n.nspname = current_schema() and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
			order by 1
			""";

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
	}

	@Test
	void allMigrationsAreAppliedSuccessfully() {
		List<String> versions = this.jdbc.queryForList(
				"select version from flyway_schema_history where version is not null and success order by installed_rank",
				String.class);
		assertThat(versions).containsExactlyElementsOf(MigrationFiles.versions());
		assertThat(this.jdbc.queryForObject("select count(*) from flyway_schema_history where not success",
				Integer.class)).isZero();
	}

	@Test
	void foundationTablesExistAndNothingElse() {
		assertThat(this.jdbc.queryForList(TABLES_SQL, String.class)).containsExactly("alert_setting", "app_user", "appointment", "audit_log", "brand",
				"cash_entry", "cash_session", "category", "customer", "customer_account_entry", "customer_return",
				"customer_return_line", "expense", "goods_receipt", "goods_receipt_line", "inventory_line", "inventory_session", "invoice_logo", "invoice_setting", "permission", "product", "purchase_order",
				"purchase_order_line", "refresh_token", "role", "role_permission", "sale", "sale_line", "stock_level",
				"stock_movement", "supplier", "supplier_payment", "supplier_payment_allocation", "user_role", "user_warehouse",
				"warehouse");
	}

	@Test
	void everyTableHasANamedUuidPrimaryKeyOrIsAJoinTable() {
		List<Map<String, Object>> rows = this.jdbc.queryForList("""
				select c.relname as table_name, con.conname as pk_name,
				       array_length(con.conkey, 1) as columns,
				       (select format_type(a.atttypid, a.atttypmod) from pg_attribute a
				        where a.attrelid = c.oid and a.attnum = con.conkey[1]) as first_type
				from pg_class c
				join pg_namespace n on n.oid = c.relnamespace
				left join pg_constraint con on con.conrelid = c.oid and con.contype = 'p'
				where n.nspname = current_schema() and c.relkind = 'r' and c.relname <> 'flyway_schema_history'
				""");
		assertThat(rows).isNotEmpty().allSatisfy(row -> {
			String table = (String) row.get("table_name");
			assertThat(row.get("pk_name")).as("clé primaire de %s", table).isEqualTo("pk_" + table);
			assertThat(row.get("first_type")).as("type de clé primaire de %s", table).isEqualTo("uuid");
		});
	}

	@Test
	void constraintNamesFollowTheConvention() {
		List<Map<String, Object>> rows = this.jdbc.queryForList("""
				select c.relname as table_name, con.conname as name, con.contype as type
				from pg_constraint con
				join pg_class c on c.oid = con.conrelid
				join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = current_schema() and con.contype in ('p', 'f', 'u', 'c')
				  and c.relname <> 'flyway_schema_history'
				""");
		assertThat(rows).isNotEmpty().allSatisfy(row -> {
			String table = (String) row.get("table_name");
			String prefix = switch (String.valueOf(row.get("type"))) {
				case "p" -> "pk_";
				case "f" -> "fk_";
				case "u" -> "uk_";
				default -> "ck_";
			};
			assertThat((String) row.get("name")).as("contrainte de %s", table).startsWith(prefix + table);
		});
	}

	@Test
	void indexNamesFollowTheConvention() {
		List<Map<String, Object>> rows = this.jdbc.queryForList("""
				select t.relname as table_name, i.relname as index_name, ix.indisunique as is_unique,
				       ix.indisprimary as is_primary
				from pg_index ix
				join pg_class i on i.oid = ix.indexrelid
				join pg_class t on t.oid = ix.indrelid
				join pg_namespace n on n.oid = t.relnamespace
				where n.nspname = current_schema() and t.relname <> 'flyway_schema_history'
				""");
		assertThat(rows).isNotEmpty().allSatisfy(row -> {
			String table = (String) row.get("table_name");
			String name = (String) row.get("index_name");
			if (Boolean.TRUE.equals(row.get("is_primary"))) {
				assertThat(name).isEqualTo("pk_" + table);
			}
			else if (Boolean.TRUE.equals(row.get("is_unique"))) {
				assertThat(name).as("index unique de %s", table).startsWith("uk_" + table);
			}
			else {
				assertThat(name).as("index de %s", table).startsWith("ix_" + table);
			}
		});
	}

	@Test
	void everyForeignKeyIsCoveredByAnIndex() {
		List<String> uncovered = this.jdbc.queryForList("""
				select con.conname
				from pg_constraint con
				join pg_class c on c.oid = con.conrelid
				join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = current_schema() and con.contype = 'f'
				  and not exists (
				    select 1 from pg_index ix
				    where ix.indrelid = con.conrelid
				      and (ix.indkey::int2[])[0:array_length(con.conkey, 1) - 1] = con.conkey)
				""", String.class);
		assertThat(uncovered).as("clés étrangères sans index de tête").isEmpty();
	}

	@Test
	void timestampsAreTimezoneAwareAndTextIsBounded() {
		List<String> naiveTimestamps = this.jdbc.queryForList("""
				select table_name || '.' || column_name from information_schema.columns
				where table_schema = current_schema() and data_type = 'timestamp without time zone'
				  and table_name <> 'flyway_schema_history'
				""", String.class);
		assertThat(naiveTimestamps).as("utiliser timestamptz").isEmpty();

		List<String> unboundedVarchars = this.jdbc.queryForList("""
				select table_name || '.' || column_name from information_schema.columns
				where table_schema = current_schema() and data_type = 'character varying'
				  and character_maximum_length is null and table_name <> 'flyway_schema_history'
				""", String.class);
		assertThat(unboundedVarchars).as("varchar sans longueur").isEmpty();
	}

	@Test
	void mutableTablesCarryTheStandardAuditColumns() {
		List<String> tablesWithVersion = this.jdbc.queryForList("""
				select table_name from information_schema.columns
				where table_schema = current_schema() and column_name = 'version'
				  and table_name <> 'flyway_schema_history'
				""", String.class);
		assertThat(tablesWithVersion).containsExactlyInAnyOrder("alert_setting", "app_user", "appointment", "brand", "cash_session", "category", "customer", "expense", "inventory_session", "invoice_logo", "invoice_setting", "permission", "product",
				"purchase_order", "role", "sale", "supplier", "warehouse");

		for (String table : tablesWithVersion) {
			List<String> columns = this.jdbc.queryForList("""
					select column_name from information_schema.columns
					where table_schema = current_schema() and table_name = ?
					""", String.class, table);
			assertThat(columns).as("colonnes standard de %s", table)
				.contains("id", "version", "created_at", "created_by", "updated_at", "updated_by");
		}
	}

	@Test
	void everyTableIsDocumented() {
		List<String> undocumented = this.jdbc.queryForList(TABLES_SQL.replace("order by 1",
				"and obj_description(c.oid, 'pg_class') is null order by 1"), String.class);
		assertThat(undocumented).as("tables sans COMMENT ON TABLE").isEmpty();
	}

	@Test
	void referenceDataIsLoaded() {
		assertThat(this.jdbc.queryForList("select code from role where built_in order by code", String.class))
			.containsExactly("ADMIN", "CAISSIER", "GESTIONNAIRE");
		assertThat(this.jdbc.queryForObject("select count(*) from permission", Integer.class)).isEqualTo(48);

		Map<String, Object> matrix = this.jdbc.queryForMap("""
				select count(*) filter (where r.code = 'ADMIN') as admin,
				       count(*) filter (where r.code = 'GESTIONNAIRE') as gestionnaire,
				       count(*) filter (where r.code = 'CAISSIER') as caissier
				from role_permission rp join role r on r.id = rp.role_id
				""");
		// Gestionnaire : 40 droits en V021, moins warehouse:all et warehouse:manage en V029.
		assertThat(matrix).containsEntry("admin", 48L).containsEntry("gestionnaire", 38L).containsEntry("caissier", 15L);

		assertThat(this.jdbc.queryForObject("select name from warehouse where code = 'MAG-01'", String.class))
			.isEqualTo("Magasin principal");
	}

}
