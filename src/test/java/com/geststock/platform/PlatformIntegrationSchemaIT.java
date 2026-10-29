package com.geststock.platform;

import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registre des liaisons dans la base plateforme (lot 2, étape G2 ; plan § 27, v14-D2, D3, D5, D6, D9, D10,
 * D11, D14 et ajustements v14) : tables, colonnes, conventions de nommage, commentaires, contraintes de
 * cohérence, unicité côté boutique et retour arrière V002 → U002 → V002.
 *
 * <p>Les contrôles de schéma portent sur la base plateforme de l'application de test, c'est-à-dire le schéma
 * {@code platform} de la base historique (URL plateforme vide en test). Le retour arrière est joué dans des
 * bases jetables, dans les deux dispositions : base plateforme séparée, et schéma {@code platform} à côté des
 * tables d'une base historique. Chaque écriture est annulée : aucune ligne ne reste dans la base partagée.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class PlatformIntegrationSchemaIT {

	private static final List<String> TABLES = List.of("integration_authorization_code",
			"integration_authorization_request", "integration_client", "integration_client_redirect_uri",
			"integration_link", "integration_rate_limit");

	private static final String SHA_A = "a".repeat(64);

	private static final String SHA_B = "b".repeat(64);

	private static final String REDIRECT = "https://www.jecreemaboutique.com/integrations/geststock/callback";

	@Autowired
	private PlatformDb platform;

	@Autowired
	private ProvisionerAccess provisioner;

	// --- Tables, colonnes, nommage, commentaires ----------------------------------------------------------

	@Test
	void tablesAndColumnsMatchTheDesign() {
		Map<String, String> expected = new LinkedHashMap<>();
		column(expected, "integration_client", "id uuid", "client_id varchar(64)", "name varchar(100)",
				"secret_hash varchar(64)", "secondary_secret_hash varchar(64) null", "allowed_scopes varchar[]", "status varchar(20)", "created_at timestamptz",
				"created_by uuid null", "updated_at timestamptz", "disabled_at timestamptz null");
		column(expected, "integration_client_redirect_uri", "id uuid", "client_id uuid", "redirect_uri varchar(2000)",
				"created_at timestamptz");
		column(expected, "integration_authorization_request", "id uuid", "request_uri_hash varchar(64)",
				"client_id uuid", "external_shop_id varchar(64)", "shop_name varchar(200)", "redirect_uri varchar(2000)",
				"code_challenge varchar(128)", "code_challenge_method varchar(10)", "scopes varchar[]",
				"state varchar(512)", "expires_at timestamptz", "decision varchar(10) null", "used_at timestamptz null",
				"created_at timestamptz");
		column(expected, "integration_link", "id uuid", "client_id uuid", "company_id uuid",
				"external_shop_id varchar(64)", "shop_name varchar(200)", "status varchar(20)",
				"suspension_reason varchar(30) null", "revocation_reason varchar(30) null", "currency varchar(3)",
				"scopes varchar[]", "authorized_by uuid", "authorized_by_username varchar(50)",
				"secret_hash varchar(64) null", "pending_secret_hash varchar(64) null",
				"pending_secret_created_at timestamptz null", "created_at timestamptz", "activated_at timestamptz null",
				"suspended_at timestamptz null", "revoked_at timestamptz null", "updated_at timestamptz");
		column(expected, "integration_authorization_code", "id uuid", "code_hash varchar(64)", "request_id uuid",
				"link_id uuid", "expires_at timestamptz", "used_at timestamptz null", "created_at timestamptz");
		column(expected, "integration_rate_limit", "counter_key varchar(200)", "window_start timestamptz",
				"hits integer");

		Map<String, String> actual = new LinkedHashMap<>();
		this.platform.jdbc().sql("""
				select table_name, column_name, data_type, udt_name, character_maximum_length, is_nullable
				from information_schema.columns
				where table_schema = 'platform' and table_name like 'integration\\_%'
				order by table_name, ordinal_position""").query((rs, n) -> {
			String type = switch (rs.getString("data_type")) {
				case "character varying" -> "varchar(" + rs.getInt("character_maximum_length") + ")";
				case "timestamp with time zone" -> "timestamptz";
				case "ARRAY" -> rs.getString("udt_name").substring(1) + "[]";
				default -> rs.getString("data_type");
			};
			actual.put(rs.getString("table_name") + "." + rs.getString("column_name"),
					type + ("YES".equals(rs.getString("is_nullable")) ? " null" : ""));
			return null;
		}).list();

		assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
	}

	@Test
	void constraintAndIndexNamesFollowTheConventions() {
		List<Map<String, Object>> constraints = this.platform.jdbc().sql("""
				select c.relname as table_name, con.conname as name, con.contype as type
				from pg_constraint con join pg_class c on c.oid = con.conrelid
				join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'platform' and c.relname like 'integration\\_%' and con.contype in ('p', 'f', 'u', 'c')""")
			.query().listOfRows();
		assertThat(constraints).isNotEmpty().allSatisfy(row -> {
			String prefix = switch (String.valueOf(row.get("type"))) {
				case "p" -> "pk_";
				case "f" -> "fk_";
				case "u" -> "uk_";
				default -> "ck_";
			};
			assertThat((String) row.get("name")).as("contrainte de %s", row.get("table_name"))
				.startsWith(prefix + row.get("table_name"));
		});

		List<Map<String, Object>> indexes = this.platform.jdbc().sql("""
				select t.relname as table_name, i.relname as name, ix.indisunique as is_unique, ix.indisprimary as is_primary
				from pg_index ix join pg_class i on i.oid = ix.indexrelid join pg_class t on t.oid = ix.indrelid
				join pg_namespace n on n.oid = t.relnamespace
				where n.nspname = 'platform' and t.relname like 'integration\\_%'""").query().listOfRows();
		assertThat(indexes).isNotEmpty().allSatisfy(row -> {
			String table = (String) row.get("table_name");
			String name = (String) row.get("name");
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

		assertThat(this.platform.jdbc().sql("""
				select con.conname from pg_constraint con
				join pg_class c on c.oid = con.conrelid join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'platform' and c.relname like 'integration\\_%' and con.contype = 'f'
				  and not exists (
				    select 1 from pg_index ix
				    where ix.indrelid = con.conrelid and ix.indpred is null
				      and (ix.indkey::int2[])[0:array_length(con.conkey, 1) - 1] = con.conkey)""")
			.query(String.class).list()).as("clés étrangères sans index complet de tête").isEmpty();
	}

	@Test
	void everyTableAndColumnIsCommented() {
		assertThat(this.platform.jdbc().sql("""
				select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'platform' and c.relkind = 'r' and c.relname like 'integration\\_%'
				order by 1""").query(String.class).list()).containsExactlyElementsOf(TABLES);
		assertThat(this.platform.jdbc().sql("""
				select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'platform' and c.relkind = 'r' and c.relname like 'integration\\_%'
				  and obj_description(c.oid, 'pg_class') is null""").query(String.class).list())
			.as("tables sans COMMENT ON TABLE").isEmpty();
		assertThat(this.platform.jdbc().sql("""
				select c.relname || '.' || a.attname from pg_attribute a
				join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'platform' and c.relkind = 'r' and c.relname like 'integration\\_%'
				  and a.attnum > 0 and not a.attisdropped and col_description(c.oid, a.attnum) is null""")
			.query(String.class).list()).as("colonnes sans COMMENT ON COLUMN").isEmpty();
	}

	// --- Contraintes de cohérence -------------------------------------------------------------------------

	@Test
	void validRowsAreAcceptedInEveryCoherentState() {
		inRolledBackTransaction(j -> {
			Fixture f = fixture(j);
			insertLink(j, f, Map.of("external_shop_id", "'shop-pending'", "status", "'PENDING'", "secret_hash", "NULL",
					"activated_at", "NULL"));
			insertLink(j, f, Map.of("external_shop_id", "'shop-active'"));
			insertLink(j, f, Map.of("external_shop_id", "'shop-rotating'", "pending_secret_hash", "'" + SHA_B + "'",
					"pending_secret_created_at", "now()"));
			insertLink(j, f, Map.of("external_shop_id", "'shop-suspended'", "status", "'SUSPENDED'",
					"suspension_reason", "'AUTHORIZER_LOST_ACCESS'", "suspended_at", "now()"));
			insertLink(j, f, Map.of("external_shop_id", "'shop-revoked'", "status", "'REVOKED'", "secret_hash", "NULL",
					"revocation_reason", "'SHOP_REQUEST'", "revoked_at", "now()"));
			insertLink(j, f, Map.of("external_shop_id", "'shop-replayed'", "status", "'REVOKED'", "secret_hash", "NULL",
					"revocation_reason", "'CODE_REPLAYED'", "revoked_at", "now()"));
			insertLink(j, f, Map.of("external_shop_id", "'shop-expired'", "status", "'REVOKED'", "secret_hash", "NULL",
					"activated_at", "NULL", "revocation_reason", "'EXPIRED'", "revoked_at", "now()"));
			UUID request = insertRequest(j, f, SHA_A);
			UUID link = insertLink(j, f, Map.of("external_shop_id", "'shop-coded'", "status", "'PENDING'",
					"secret_hash", "NULL", "activated_at", "NULL"));
			j.sql("""
					insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at)
					values (:hash, :request, :link, now() + interval '5 minutes')""")
				.param("hash", SHA_B).param("request", request).param("link", link).update();
			j.sql("insert into platform.integration_rate_limit (counter_key, window_start, hits) values ('oauth-token:client:x', now(), 1)")
				.update();
		});
	}

	@Test
	void clientChecksRejectInvalidValues() {
		rejects("ck_integration_client_status", j -> insertClient(j, Map.of("status", "'UNKNOWN'")));
		rejects("ck_integration_client_disabled", j -> insertClient(j, Map.of("status", "'DISABLED'")));
		rejects("ck_integration_client_disabled", j -> insertClient(j, Map.of("disabled_at", "now()")));
		rejects("ck_integration_client_secret_hash", j -> insertClient(j, Map.of("secret_hash", "'abc'")));
		rejects("ck_integration_client_secret_hash", j -> insertClient(j, Map.of("secret_hash", "'" + "A".repeat(64) + "'")));
		rejects("ck_integration_client_secondary_secret_hash", j -> insertClient(j, Map.of("secondary_secret_hash", "'abc'")));
		rejects("ck_integration_client_secondary_secret_hash",
				j -> insertClient(j, Map.of("secondary_secret_hash", "'" + "B".repeat(64) + "'")));
		rejects("ck_integration_client_secondary_secret_distinct",
				j -> insertClient(j, Map.of("secondary_secret_hash", "'" + SHA_A + "'")));
		inRolledBackTransaction(j -> insertClient(j, Map.of("secondary_secret_hash", "NULL")));
		inRolledBackTransaction(j -> insertClient(j, Map.of("secondary_secret_hash", "'" + SHA_B + "'")));
		rejects("ck_integration_client_scopes", j -> insertClient(j, Map.of("allowed_scopes", "ARRAY['admin:all']::varchar[]")));
		rejects("ck_integration_client_scopes", j -> insertClient(j, Map.of("allowed_scopes", "ARRAY[]::varchar[]")));
		rejects("ck_integration_client_client_id", j -> insertClient(j, Map.of("client_id", "'Bad Id'")));
		rejects("uk_integration_client_client_id", j -> {
			insertClient(j, Map.of("client_id", "'jcmb-dup'"));
			insertClient(j, Map.of("client_id", "'jcmb-dup'"));
		});
		rejects("ck_integration_client_redirect_uri_format", j -> insertRedirect(j, insertClient(j, Map.of()), "http://evil.example/cb"));
		rejects("ck_integration_client_redirect_uri_format", j -> insertRedirect(j, insertClient(j, Map.of()), REDIRECT + "#x"));
		rejects("uk_integration_client_redirect_uri", j -> {
			UUID client = insertClient(j, Map.of());
			insertRedirect(j, client, REDIRECT);
			insertRedirect(j, client, REDIRECT);
		});
		inRolledBackTransaction(j -> insertRedirect(j, insertClient(j, Map.of()), "http://localhost:4200/integrations/geststock/callback"));
	}

	@Test
	void authorizationRequestChecksRejectInvalidValues() {
		rejects("ck_integration_authorization_request_method", j -> insertRequest(j, fixture(j), SHA_A, Map.of("code_challenge_method", "'plain'")));
		rejects("ck_integration_authorization_request_code_challenge", j -> insertRequest(j, fixture(j), SHA_A, Map.of("code_challenge", "'short'")));
		rejects("ck_integration_authorization_request_state", j -> insertRequest(j, fixture(j), SHA_A, Map.of("state", "repeat('s', 31)")));
		assertThatThrownBy(() -> inRolledBackTransaction(j -> insertRequest(j, fixture(j), SHA_A, Map.of("state", "repeat('s', 513)"))))
			.as("state de plus de 512 caractères").isInstanceOf(DataIntegrityViolationException.class);
		inRolledBackTransaction(j -> insertRequest(j, fixture(j), SHA_A, Map.of("state", "repeat('s', 512)")));
		rejects("ck_integration_authorization_request_request_uri_hash", j -> insertRequest(j, fixture(j), "xyz"));
		rejects("ck_integration_authorization_request_scopes", j -> insertRequest(j, fixture(j), SHA_A, Map.of("scopes", "ARRAY['root']::varchar[]")));
		rejects("ck_integration_authorization_request_decision", j -> insertRequest(j, fixture(j), SHA_A, Map.of("decision", "'APPROVED'")));
		rejects("ck_integration_authorization_request_decision", j -> insertRequest(j, fixture(j), SHA_A, Map.of("used_at", "now()")));
		rejects("ck_integration_authorization_request_decision", j -> insertRequest(j, fixture(j), SHA_A, Map.of("decision", "'MAYBE'", "used_at", "now()")));
		rejects("ck_integration_authorization_request_expiry", j -> insertRequest(j, fixture(j), SHA_A, Map.of("expires_at", "now() - interval '1 second'")));
		rejects("ck_integration_authorization_request_shop", j -> insertRequest(j, fixture(j), SHA_A, Map.of("external_shop_id", "' '")));
		rejects("fk_integration_authorization_request_redirect_uri",
				j -> insertRequest(j, fixture(j), SHA_A, Map.of("redirect_uri", "'https://www.jecreemaboutique.com/autre'")));
		rejects("uk_integration_authorization_request_request_uri_hash", j -> {
			Fixture f = fixture(j);
			insertRequest(j, f, SHA_A);
			insertRequest(j, f, SHA_A);
		});
	}

	@Test
	void linkChecksRejectInvalidValues() {
		rejects("ck_integration_link_status", j -> insertLink(j, fixture(j), Map.of("status", "'DELETED'", "secret_hash", "NULL")));
		rejects("ck_integration_link_currency", j -> insertLink(j, fixture(j), Map.of("currency", "'xof'")));
		rejects("ck_integration_link_currency", j -> insertLink(j, fixture(j), Map.of("currency", "'XO'")));
		rejects("ck_integration_link_scopes", j -> insertLink(j, fixture(j), Map.of("scopes", "ARRAY[]::varchar[]")));
		rejects("ck_integration_link_secret_format", j -> insertLink(j, fixture(j), Map.of("secret_hash", "'not-a-hash'")));
		rejects("ck_integration_link_shop", j -> insertLink(j, fixture(j), Map.of("external_shop_id", "''")));
		rejects("fk_integration_link_company", j -> insertLink(j, new Fixture(UUID.randomUUID(), fixture(j).client()), Map.of()));
		rejects("fk_integration_link_client", j -> insertLink(j, new Fixture(fixture(j).company(), UUID.randomUUID()), Map.of()));
	}

	@Test
	void suspensionAndRevocationReasonsAreCoherentWithTheStatus() {
		rejects("ck_integration_link_suspension_reason", j -> insertLink(j, fixture(j), Map.of("status", "'SUSPENDED'",
				"suspension_reason", "'OTHER'", "suspended_at", "now()")));
		rejects("ck_integration_link_suspended", j -> insertLink(j, fixture(j), Map.of("status", "'SUSPENDED'",
				"suspended_at", "now()")));
		rejects("ck_integration_link_suspended", j -> insertLink(j, fixture(j), Map.of("status", "'SUSPENDED'",
				"suspension_reason", "'MANUAL'")));
		rejects("ck_integration_link_suspended", j -> insertLink(j, fixture(j), Map.of("suspension_reason", "'MANUAL'")));

		rejects("ck_integration_link_revocation_reason", j -> insertLink(j, fixture(j), Map.of("status", "'REVOKED'",
				"secret_hash", "NULL", "revocation_reason", "'OTHER'", "revoked_at", "now()")));
		rejects("ck_integration_link_revoked", j -> insertLink(j, fixture(j), Map.of("status", "'REVOKED'",
				"secret_hash", "NULL", "revocation_reason", "'SHOP_REQUEST'")));
		rejects("ck_integration_link_revoked", j -> insertLink(j, fixture(j), Map.of("status", "'REVOKED'",
				"secret_hash", "NULL", "revoked_at", "now()")));
		rejects("ck_integration_link_revoked", j -> insertLink(j, fixture(j), Map.of("revoked_at", "now()")));
		rejects("ck_integration_link_revoked", j -> insertLink(j, fixture(j), Map.of("revocation_reason", "'GESTSTOCK_ADMIN'")));
	}

	@Test
	void activationAndSecretsAreCoherentWithTheStatus() {
		rejects("ck_integration_link_activated", j -> insertLink(j, fixture(j), Map.of("activated_at", "NULL")));
		rejects("ck_integration_link_activated", j -> insertLink(j, fixture(j), Map.of("status", "'PENDING'",
				"secret_hash", "NULL")));
		rejects("ck_integration_link_activated", j -> insertLink(j, fixture(j), Map.of("status", "'REVOKED'",
				"secret_hash", "NULL", "revocation_reason", "'EXPIRED'", "revoked_at", "now()")));

		rejects("ck_integration_link_secret", j -> insertLink(j, fixture(j), Map.of("secret_hash", "NULL")));
		rejects("ck_integration_link_secret", j -> insertLink(j, fixture(j), Map.of("status", "'PENDING'",
				"activated_at", "NULL")));
		rejects("ck_integration_link_secret", j -> insertLink(j, fixture(j), Map.of("status", "'REVOKED'",
				"revocation_reason", "'SHOP_REQUEST'", "revoked_at", "now()")));
		rejects("ck_integration_link_pending_secret", j -> insertLink(j, fixture(j), Map.of("pending_secret_hash",
				"'" + SHA_B + "'")));
		rejects("ck_integration_link_pending_secret", j -> insertLink(j, fixture(j), Map.of("pending_secret_created_at",
				"now()")));
	}

	@Test
	void aShopHasAtMostOneNonRevokedLinkPerClient() {
		for (String status : List.of("PENDING", "ACTIVE", "SUSPENDED")) {
			rejects("uk_integration_link_active_shop", j -> {
				Fixture f = fixture(j);
				insertLink(j, f, stateOf(status));
				insertLink(j, f, stateOf("PENDING"));
			});
		}
		inRolledBackTransaction(j -> {
			Fixture f = fixture(j);
			insertLink(j, f, stateOf("REVOKED"));
			insertLink(j, f, stateOf("REVOKED"));
			insertLink(j, f, stateOf("PENDING"));
			// Même boutique, autre client (autre environnement JeCréeMaBoutique) : accepté.
			insertLink(j, new Fixture(f.company(), insertClient(j, Map.of())), stateOf("ACTIVE"));
			// Une entreprise peut avoir plusieurs boutiques (v14-D2).
			insertLink(j, f, Map.of("external_shop_id", "'autre-boutique'"));
		});
	}

	@Test
	void authorizationCodeChecksRejectInvalidValues() {
		rejects("ck_integration_authorization_code_code_hash", j -> insertCode(j, "xyz", "now() + interval '5 minutes'"));
		rejects("ck_integration_authorization_code_expiry", j -> insertCode(j, SHA_A, "now()"));
		rejects("ck_integration_rate_limit_hits",
				j -> j.sql("insert into platform.integration_rate_limit (counter_key, window_start, hits) values ('k', now(), -1)").update());
		rejects("ck_integration_rate_limit_key",
				j -> j.sql("insert into platform.integration_rate_limit (counter_key, window_start, hits) values ('', now(), 0)").update());
		rejects("pk_integration_rate_limit", j -> {
			j.sql("insert into platform.integration_rate_limit (counter_key, window_start, hits) values ('k', timestamptz '2026-10-29 10:00:00+00', 1)").update();
			j.sql("insert into platform.integration_rate_limit (counter_key, window_start, hits) values ('k', timestamptz '2026-10-29 10:00:00+00', 1)").update();
		});
	}

	// --- Retour arrière V002 → U002 → V002 (v14-D9) -----------------------------------------------------

	@ParameterizedTest(name = "schéma platform dans une base historique : {0}")
	@ValueSource(booleans = { false, true })
	void v002IsRolledBackThenReapplied(boolean besideALegacyDatabase) throws Exception {
		String database = "gs_platform_rb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		try (Connection c = this.provisioner.server().getConnection(); Statement s = c.createStatement()) {
			s.execute("CREATE DATABASE " + database);
		}
		try {
			DataSource scratch = this.provisioner.database(database);
			JdbcTemplate jdbc = new JdbcTemplate(scratch);
			int legacyTables = 0;
			if (besideALegacyDatabase) {
				Flyway.configure().dataSource(scratch).locations("classpath:db/migration").cleanDisabled(true).load()
					.migrate();
				legacyTables = publicTables(jdbc);
				assertThat(legacyTables).as("tables de la base historique").isGreaterThan(40);
			}

			assertThat(platformFlyway(scratch).target("1").load().migrate().migrationsExecuted).isEqualTo(1);
			assertThat(integrationTables(jdbc)).isEmpty();
			assertThat(platformFlyway(scratch).load().migrate().migrationsExecuted).isEqualTo(1);
			assertThat(integrationTables(jdbc)).containsExactlyElementsOf(TABLES);

			try (Connection connection = scratch.getConnection()) {
				ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/platform-rollback/U002__integration_link.sql"));
			}
			assertThat(integrationTables(jdbc)).as("tables supprimées par U002").isEmpty();
			assertThat(jdbc.queryForList("select version from platform.flyway_schema_history where version is not null order by installed_rank",
					String.class)).as("historique de la plateforme").containsExactly("001");
			assertThat(jdbc.queryForObject("select count(*) from platform.company", Integer.class)).as("V001 intacte").isZero();

			assertThat(platformFlyway(scratch).load().migrate().migrationsExecuted).as("ré-application de V002").isEqualTo(1);
			assertThat(integrationTables(jdbc)).containsExactlyElementsOf(TABLES);
			if (besideALegacyDatabase) {
				assertThat(publicTables(jdbc)).as("base historique inchangée").isEqualTo(legacyTables);
			}
		}
		finally {
			try (Connection c = this.provisioner.server().getConnection(); Statement s = c.createStatement()) {
				s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
			}
		}
	}

	private static org.flywaydb.core.api.configuration.FluentConfiguration platformFlyway(DataSource dataSource) {
		// Même configuration que PlatformDb.
		return Flyway.configure().dataSource(dataSource).schemas(PlatformDb.SCHEMA).defaultSchema(PlatformDb.SCHEMA)
			.createSchemas(true).locations("classpath:db/platform").validateOnMigrate(true).cleanDisabled(true);
	}

	private static List<String> integrationTables(JdbcTemplate jdbc) {
		return jdbc.queryForList("select tablename from pg_tables where schemaname = 'platform' and tablename like 'integration\\_%' order by 1",
				String.class);
	}

	private static int publicTables(JdbcTemplate jdbc) {
		return jdbc.queryForObject("select count(*) from pg_tables where schemaname = 'public'", Integer.class);
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private record Fixture(UUID company, UUID client) {
	}

	private void inRolledBackTransaction(Consumer<JdbcClient> work) {
		this.platform.inTransaction(status -> {
			status.setRollbackOnly();
			work.accept(this.platform.jdbc());
			return null;
		});
	}

	private void rejects(String constraint, Consumer<JdbcClient> work) {
		assertThatThrownBy(() -> inRolledBackTransaction(work)).as(constraint)
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining(constraint);
	}

	private static Fixture fixture(JdbcClient j) {
		UUID company = j.sql("""
				insert into platform.company (code, legal_name, trade_name, owner_last_name, admin_username)
				values (:code, 'Essai', 'Essai', 'Essai', 'admin') returning id""")
			.param("code", "it-link-" + UUID.randomUUID().toString().substring(0, 8))
			.query(UUID.class).single();
		UUID client = insertClient(j, Map.of());
		insertRedirect(j, client, REDIRECT);
		return new Fixture(company, client);
	}

	private static UUID insertClient(JdbcClient j, Map<String, String> overrides) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("client_id", "'jcmb-" + UUID.randomUUID().toString().substring(0, 8) + "'");
		values.put("name", "'JeCréeMaBoutique (essai)'");
		values.put("secret_hash", "'" + SHA_A + "'");
		values.put("allowed_scopes", "ARRAY['catalog:read','orders:write']::varchar[]");
		values.put("status", "'ACTIVE'");
		values.putAll(overrides);
		return j.sql(insert("integration_client", values)).query(UUID.class).single();
	}

	private static void insertRedirect(JdbcClient j, UUID client, String uri) {
		j.sql("insert into platform.integration_client_redirect_uri (client_id, redirect_uri) values (:client, :uri)")
			.param("client", client).param("uri", uri).update();
	}

	private static UUID insertRequest(JdbcClient j, Fixture f, String uriHash) {
		return insertRequest(j, f, uriHash, Map.of());
	}

	private static UUID insertRequest(JdbcClient j, Fixture f, String uriHash, Map<String, String> overrides) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("request_uri_hash", "'" + uriHash + "'");
		values.put("client_id", "'" + f.client() + "'");
		values.put("external_shop_id", "'665f1c2ab3e4d5f6a7b8c9d0'");
		values.put("shop_name", "'Boutique d''essai'");
		values.put("redirect_uri", "'" + REDIRECT + "'");
		values.put("code_challenge", "'" + "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" + "'");
		values.put("code_challenge_method", "'S256'");
		values.put("scopes", "ARRAY['catalog:read']::varchar[]");
		values.put("state", "repeat('s', 43)");
		values.put("expires_at", "now() + interval '10 minutes'");
		values.putAll(overrides);
		return j.sql(insert("integration_authorization_request", values)).query(UUID.class).single();
	}

	/** Liaison ACTIVE valide par défaut ; {@code overrides} : colonne → expression SQL ({@code NULL} compris). */
	private static UUID insertLink(JdbcClient j, Fixture f, Map<String, String> overrides) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("client_id", "'" + f.client() + "'");
		values.put("company_id", "'" + f.company() + "'");
		values.put("external_shop_id", "'665f1c2ab3e4d5f6a7b8c9d0'");
		values.put("shop_name", "'Boutique d''essai'");
		values.put("status", "'ACTIVE'");
		values.put("currency", "'XOF'");
		values.put("scopes", "ARRAY['catalog:read']::varchar[]");
		values.put("authorized_by", "gen_random_uuid()");
		values.put("authorized_by_username", "'admin'");
		values.put("secret_hash", "'" + SHA_A + "'");
		values.put("activated_at", "now()");
		values.putAll(overrides);
		return j.sql(insert("integration_link", values)).query(UUID.class).single();
	}

	private static Map<String, String> stateOf(String status) {
		return switch (status) {
			case "PENDING" -> Map.of("status", "'PENDING'", "secret_hash", "NULL", "activated_at", "NULL");
			case "SUSPENDED" -> Map.of("status", "'SUSPENDED'", "suspension_reason", "'MANUAL'", "suspended_at", "now()");
			case "REVOKED" -> Map.of("status", "'REVOKED'", "secret_hash", "NULL", "revocation_reason", "'GESTSTOCK_ADMIN'",
					"revoked_at", "now()");
			default -> Map.of();
		};
	}

	private void insertCode(JdbcClient j, String codeHash, String expiresAt) {
		Fixture f = fixture(j);
		UUID request = insertRequest(j, f, SHA_A);
		UUID link = insertLink(j, f, stateOf("PENDING"));
		j.sql("insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at) values (:hash, :request, :link, "
				+ expiresAt + ")").param("hash", codeHash).param("request", request).param("link", link).update();
	}

	private static String insert(String table, Map<String, String> values) {
		return "insert into platform." + table + " (" + String.join(", ", values.keySet()) + ") values ("
				+ String.join(", ", values.values()) + ") returning id";
	}

	private static void column(Map<String, String> expected, String table, String... columns) {
		for (String column : columns) {
			int space = column.indexOf(' ');
			expected.put(table + "." + column.substring(0, space), column.substring(space + 1));
		}
	}

}
