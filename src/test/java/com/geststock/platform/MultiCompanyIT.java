package com.geststock.platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.geststock.platform.company.CompanyDirectory;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.platform.provisioning.TenantMigrations;
import com.geststock.security.JwtTokenService;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantConnection;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plateforme multi-entreprises de bout en bout, sur un vrai PostgreSQL : provisionnement de deux bases
 * dédiées (supprimées à la fin), activation de l'administrateur, isolation des données et des rôles
 * PostgreSQL, séparation des jetons Super Admin / entreprise, suspension, reprise après échec,
 * personnalisation. Provisionnement synchrone pour des tests déterministes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiCompanyIT {

	private static final String SUPER_ADMIN = "it_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final String ADMIN_PASSWORD = "Entreprise2026";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private CompanyDirectory directory;

	@Autowired
	private TenantDataSourceRegistry pools;

	@Autowired
	private ProvisionerAccess provisioner;

	@Autowired
	private JsonMapper json;

	@MockitoSpyBean
	private TenantMigrations migrations;

	@Autowired
	private TenantSchemaRegistry schemas;

	private final String suffix = UUID.randomUUID().toString().substring(0, 6).toLowerCase(Locale.ROOT);

	private final List<UUID> createdCompanies = new ArrayList<>();

	private String superToken;

	private Company a;

	private Company b;

	@BeforeAll
	void provisionTwoCompanies() throws Exception {
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", SUPER_ADMIN)
			.param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD))
			.update();
		this.superToken = read(this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
			.andExpect(status().isOk())).get("accessToken").asString();
		this.a = provision("it-a-" + this.suffix, "Quincaillerie A", "#112233");
		this.b = provision("it-b-" + this.suffix, "Commerce B", "#445566");
	}

	/** Bases et rôles de test supprimés (jamais une base qui n'a pas été créée par ce test). */
	@AfterAll
	void dropCompanies() throws SQLException {
		for (UUID id : this.createdCompanies) {
			this.pools.close(id);
			var database = this.platform.jdbc()
				.sql("select database_name, runtime_role from platform.company_database where company_id = :id and mode = 'DEDICATED'")
				.param("id", id)
				.query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) })
				.optional();
			if (database.isPresent()) {
				try (Connection c = this.provisioner.server().getConnection(); Statement statement = c.createStatement()) {
					statement.execute("DROP DATABASE IF EXISTS \"" + database.get()[0] + "\" WITH (FORCE)");
					statement.execute("DROP ROLE IF EXISTS \"" + database.get()[1] + "\"");
				}
			}
			this.platform.inTransaction(() -> {
				for (String sql : List.of("delete from platform.provisioning_job where company_id = :id",
						"delete from platform.company_database where company_id = :id",
						"delete from platform.platform_audit_log where company_id = :id",
						"delete from platform.company where id = :id")) {
					this.platform.jdbc().sql(sql).param("id", id).update();
				}
			});
		}
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
	}

	// --- Provisionnement -----------------------------------------------------------------------------

	@Test
	void aProvisionedCompanyHasItsOwnDatabaseAndAllStepsRecorded() throws Exception {
		this.mvc.perform(get("/api/platform/companies/{id}", this.a.id()).with(bearer(this.superToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.database.mode").value("DEDICATED"))
			.andExpect(jsonPath("$.database.name", startsWith("gst_test_company_")))
			.andExpect(jsonPath("$.provisioning.status").value("SUCCEEDED"))
			.andExpect(jsonPath("$.provisioning.steps[*].step", hasItem("MIGRATIONS")))
			.andExpect(jsonPath("$.provisioning.steps[*].status", everyItem(not("FAILED"))))
			// Aucun identifiant de connexion n'est exposé.
			.andExpect(jsonPath("$.database.role").doesNotExist())
			.andExpect(jsonPath("$.database.password").doesNotExist());
	}

	@Test
	void theSameIdempotencyKeyNeverCreatesASecondCompany() throws Exception {
		String code = "it-idem-" + this.suffix;
		String key = "it-key-" + this.suffix;
		JsonNode first = read(createCompany(code, key).andExpect(status().isCreated()));
		UUID id = UUID.fromString(first.get("id").asString());
		this.createdCompanies.add(id);
		createCompany(code, key).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
		// Même code sans clé : conflit, pas de doublon.
		createCompany(code, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMPANY_CODE_ALREADY_USED"));
		assertThat(this.platform.jdbc().sql("select count(*) from platform.company where code = :c").param("c", code)
			.query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void aFailedProvisioningResumesWithoutRecreatingAnything() throws Exception {
		String code = "it-retry-" + this.suffix;
		doThrow(new IllegalStateException("Migration simulée en échec")).doCallRealMethod()
			.when(this.migrations).migrate(argThat(t -> t != null && code.equals(t.code())), any());
		JsonNode failed = read(createCompany(code, null).andExpect(status().isCreated()));
		UUID id = UUID.fromString(failed.get("id").asString());
		this.createdCompanies.add(id);
		assertThat(failed.get("status").asString()).isEqualTo("FAILED");
		assertThat(failed.get("provisioning").get("lastError").asString()).contains("Migration simulée en échec");
		// Entreprise en échec : connexion impossible.
		this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"company\": \"" + code + "\", \"username\": \"admin\", \"password\": \"x\"}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("COMPANY_UNAVAILABLE"));

		this.mvc.perform(post("/api/platform/companies/{id}/provisioning/retry", id).with(bearer(this.superToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.provisioning.attempts").value(2));
		// Reprise : base et rôle constatés, pas recréés.
		List<String> secondAttempt = this.platform.jdbc()
			.sql("""
					select s.step || ':' || s.status from platform.provisioning_step s
					join platform.provisioning_job j on j.id = s.job_id where j.company_id = :id and s.attempt = 2 order by s.occurred_at""")
			.param("id", id)
			.query(String.class)
			.list();
		assertThat(secondAttempt).contains("DATABASE_RECORD:SKIPPED", "DATABASE:SKIPPED", "MIGRATIONS:DONE");
		this.mvc.perform(post("/api/platform/companies/{id}/provisioning/retry", id).with(bearer(this.superToken)))
			.andExpect(status().isConflict());
	}

	// --- Isolation ---------------------------------------------------------------------------------

	@Test
	void eachCompanySeesOnlyItsOwnData() throws Exception {
		String category = "Cat-A-" + this.suffix;
		this.mvc.perform(post("/api/categories").with(bearer(this.a.token())).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"" + category + "\"}"))
			.andExpect(status().isCreated());
		this.mvc.perform(get("/api/categories").param("q", category).with(bearer(this.a.token())))
			.andExpect(jsonPath("$.total").value(1));
		this.mvc.perform(get("/api/categories").param("q", category).with(bearer(this.b.token())))
			.andExpect(jsonPath("$.total").value(0));
		// Utilisateurs : l'administrateur de A n'existe pas dans B.
		this.mvc.perform(get("/api/auth/me").with(bearer(this.b.token())))
			.andExpect(jsonPath("$.company.code").value(this.b.code()));
		// Le contexte d'entreprise ne survit pas à la requête.
		assertThat(TenantContext.current()).isEmpty();
	}

	@Test
	void aTokenOfCompanyAPointedAtCompanyBDesignatesNoAccount() throws Exception {
		JsonNode me = read(this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token()))).andExpect(status().isOk()));
		String forged = sign(UUID.fromString(me.get("id").asString()), this.b.id());
		this.mvc.perform(get("/api/categories").with(bearer(forged)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
		// Jeton sans type ni entreprise (ancien format) : refusé.
		this.mvc.perform(get("/api/categories").with(bearer(signLegacy(UUID.fromString(me.get("id").asString())))))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void concurrentRequestsOfTwoCompaniesNeverMixTheirDatabases() throws Exception {
		String categoryA = "Par-A-" + this.suffix;
		String categoryB = "Par-B-" + this.suffix;
		this.mvc.perform(post("/api/categories").with(bearer(this.a.token())).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"" + categoryA + "\"}")).andExpect(status().isCreated());
		this.mvc.perform(post("/api/categories").with(bearer(this.b.token())).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"" + categoryB + "\"}")).andExpect(status().isCreated());
		ExecutorService executor = Executors.newFixedThreadPool(8);
		try {
			List<Callable<Boolean>> calls = new ArrayList<>();
			for (int i = 0; i < 40; i++) {
				Company company = i % 2 == 0 ? this.a : this.b;
				String own = company == this.a ? categoryA : categoryB;
				String other = company == this.a ? categoryB : categoryA;
				calls.add(() -> {
					JsonNode page = read(this.mvc.perform(get("/api/categories").param("status", "ALL").param("size", "200")
						.with(bearer(company.token()))).andExpect(status().isOk()));
					List<String> names = new ArrayList<>();
					page.get("items").forEach(item -> names.add(item.get("name").asString()));
					return names.contains(own) && !names.contains(other);
				});
			}
			for (Future<Boolean> result : executor.invokeAll(calls)) {
				assertThat(result.get()).as("chaque requête ne voit que sa base").isTrue();
			}
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void theRuntimeRoleOfACompanyCanNeitherReachAnotherDatabaseNorChangeTheSchema() throws Exception {
		TenantConnection connectionA = this.directory.connection(this.a.id());
		TenantConnection connectionB = this.directory.connection(this.b.id());
		// Rôle de A vers la base de B : connexion refusée par PostgreSQL.
		assertThatThrownBy(() -> DriverManager.getConnection(connectionB.jdbcUrl(), connectionA.username(), connectionA.password())
			.close()).isInstanceOf(SQLException.class);
		// Rôle de A sur sa propre base : DML seulement.
		try (Connection c = DriverManager.getConnection(connectionA.jdbcUrl(), connectionA.username(), connectionA.password());
				Statement statement = c.createStatement()) {
			assertThatThrownBy(() -> statement.execute("create table intrus (id int)")).isInstanceOf(SQLException.class);
			assertThatThrownBy(() -> statement.execute("drop table product")).isInstanceOf(SQLException.class);
		}
	}

	@Test
	void theRuntimeRoleOfACompanyCanWriteTheIntegrationFeed() throws Exception {
		TenantConnection connection = this.directory.connection(this.a.id());
		try (Connection c = DriverManager.getConnection(connection.jdbcUrl(), connection.username(), connection.password())) {
			c.setAutoCommit(false);
			try (Statement statement = c.createStatement()) {
				// Écriture d'un événement (seq attribué plus tard) et verrou puis mise à jour de l'état du flux.
				statement.execute("""
						insert into integration_outbox (type, operation, object_id, object_version)
						values ('CATEGORY', 'UPSERT', gen_random_uuid(), 1)""");
				statement.execute("select last_seq from integration_feed_state for update skip locked");
				assertThat(statement.executeUpdate("update integration_feed_state set last_seq = last_seq")).isEqualTo(1);
			}
			finally {
				c.rollback();
			}
		}
	}

	// --- Jetons et rôles ---------------------------------------------------------------------------

	@Test
	void superAdminAndCompanyTokensAreNeverInterchangeable() throws Exception {
		this.mvc.perform(get("/api/categories").with(bearer(this.superToken))).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/auth/me").with(bearer(this.superToken))).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/platform/companies").with(bearer(this.a.token()))).andExpect(status().isForbidden());
		this.mvc.perform(post("/api/platform/companies/{id}/suspend", this.b.id()).with(bearer(this.a.token()))
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"tentative\"}")).andExpect(status().isForbidden());
		// Aucun rôle Super Admin dans une base d'entreprise : impossible à attribuer.
		JsonNode me = read(this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token()))).andExpect(status().isOk()));
		this.mvc.perform(put("/api/users/{id}/roles", me.get("id").asString()).with(bearer(this.a.token()))
			.contentType(MediaType.APPLICATION_JSON).content("{\"roles\": [\"SUPER_ADMIN\"]}"))
			.andExpect(status().is4xxClientError());
		this.mvc.perform(get("/api/platform/companies").with(bearer(this.a.token()))).andExpect(status().isForbidden());
	}

	@Test
	void aSuspendedCompanyIsExplainedAndRestoredOnReactivation() throws Exception {
		Company c = provision("it-susp-" + this.suffix, "Boutique suspendue", "#778899");
		this.mvc.perform(post("/api/platform/companies/{id}/suspend", c.id()).with(bearer(this.superToken))
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"Abonnement impayé\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
		this.mvc.perform(get("/api/categories").with(bearer(c.token())))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("COMPANY_SUSPENDED"));
		login(c.code(), "admin", ADMIN_PASSWORD).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("COMPANY_SUSPENDED"));
		this.mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"company\": \"" + c.code() + "\", \"refreshToken\": \"" + c.refreshToken() + "\"}"))
			.andExpect(status().isForbidden());
		// Marque publique toujours lisible (écran explicatif), données intactes.
		this.mvc.perform(get("/api/public/companies/{code}/branding", c.code()))
			.andExpect(jsonPath("$.status").value("SUSPENDED"));

		this.mvc.perform(post("/api/platform/companies/{id}/reactivate", c.id()).with(bearer(this.superToken)))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		login(c.code(), "admin", ADMIN_PASSWORD).andExpect(status().isOk());
		// Sessions d'avant la suspension : révoquées.
		this.mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{\"company\": \"" + c.code() + "\", \"refreshToken\": \"" + c.refreshToken() + "\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unknownCompaniesAndUsedActivationLinksAreRefused() throws Exception {
		login("inconnue-" + this.suffix, "admin", ADMIN_PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		JsonNode link = read(this.mvc.perform(post("/api/platform/companies/{id}/admin-access-link", this.a.id())
			.with(bearer(this.superToken))).andExpect(status().isOk()));
		String token = link.get("token").asString();
		activate(this.a.code(), token, "Nouveau2026a").andExpect(status().isNoContent());
		activate(this.a.code(), token, "Encore2026b").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("ACTIVATION_TOKEN_INVALID"));
		// Le lien de A ne vaut rien dans B.
		JsonNode linkA = read(this.mvc.perform(post("/api/platform/companies/{id}/admin-access-link", this.a.id())
			.with(bearer(this.superToken))).andExpect(status().isOk()));
		activate(this.b.code(), linkA.get("token").asString(), "Croise2026c").andExpect(status().isBadRequest());
		activate(this.a.code(), linkA.get("token").asString(), ADMIN_PASSWORD).andExpect(status().isNoContent());
		// Mot de passe redéfini : les sessions précédentes de A sont fermées ; nouvelle session pour les autres tests.
		this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token()))).andExpect(status().isUnauthorized());
		JsonNode session = read(login(this.a.code(), "admin", ADMIN_PASSWORD).andExpect(status().isOk()));
		this.a = new Company(this.a.id(), this.a.code(), session.get("accessToken").asString(),
				session.get("refreshToken").asString());
	}

	// --- Personnalisation -------------------------------------------------------------------------

	@Test
	void brandingIsIndependentPerCompanyAndValidated() throws Exception {
		this.mvc.perform(get("/api/public/companies/{code}/branding", this.a.code()))
			.andExpect(jsonPath("$.primaryColor").value("#112233"))
			.andExpect(jsonPath("$.name").value("Quincaillerie A"));
		this.mvc.perform(get("/api/public/companies/{code}/branding", this.b.code()))
			.andExpect(jsonPath("$.primaryColor").value("#445566"));
		this.mvc.perform(get("/api/company/branding").with(bearer(this.b.token())))
			.andExpect(jsonPath("$.companyCode").value(this.b.code()))
			.andExpect(jsonPath("$.primaryColor").value("#445566"));

		this.mvc.perform(put("/api/platform/companies/{id}/branding", this.a.id()).with(bearer(this.superToken))
			.contentType(MediaType.APPLICATION_JSON).content(branding("red; background:url(x)")))
			.andExpect(status().isBadRequest());

		// L'entreprise ne modifie sa personnalisation qu'avec l'accord de GestStock.
		this.mvc.perform(put("/api/company/branding").with(bearer(this.a.token())).contentType(MediaType.APPLICATION_JSON)
			.content(branding("#AA0000")))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("BRANDING_NOT_EDITABLE"));
		this.mvc.perform(put("/api/platform/companies/{id}/branding/permission", this.a.id()).with(bearer(this.superToken))
			.contentType(MediaType.APPLICATION_JSON).content("{\"companyCanEdit\": true}")).andExpect(status().isOk());
		this.mvc.perform(put("/api/company/branding").with(bearer(this.a.token())).contentType(MediaType.APPLICATION_JSON)
			.content(branding("#aa0000")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.primaryColor").value("#AA0000"));
		this.mvc.perform(get("/api/company/branding").with(bearer(this.b.token())))
			.andExpect(jsonPath("$.primaryColor").value("#445566"));

		// Logo : contenu vérifié, pas le nom ni le type déclaré.
		this.mvc.perform(multipart("/api/platform/companies/{id}/branding/logo", this.a.id()).file(
				new MockMultipartFile("file", "logo.png", "image/png", "<svg onload=alert(1)>".getBytes()))
			.with(bearer(this.superToken)))
			.andExpect(status().isBadRequest());
		this.mvc.perform(multipart("/api/platform/companies/{id}/branding/logo", this.a.id()).file(
				new MockMultipartFile("file", "logo.png", "image/png", tinyPng()))
			.with(bearer(this.superToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.logoUrl", startsWith("/api/public/companies/" + this.a.code() + "/logo")));
		this.mvc.perform(get("/api/public/companies/{code}/logo", this.a.code()))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
				.string("X-Content-Type-Options", "nosniff"));
		this.mvc.perform(get("/api/public/companies/{code}/logo", this.b.code())).andExpect(status().isNotFound());
	}

	// --- Mise à jour des bases après un déploiement -------------------------------------------------

	@Test
	void theSchemaUpgradeIsReservedToTheSuperAdminAndChecksEveryActiveCompany() throws Exception {
		this.mvc.perform(post("/api/platform/schema-upgrades").with(bearer(this.a.token())))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/platform/schema-upgrades").with(bearer(this.a.token())))
			.andExpect(status().isForbidden());

		this.mvc.perform(post("/api/platform/schema-upgrades").with(bearer(this.superToken)))
			.andExpect(status().isAccepted());
		JsonNode done = awaitSchemaUpgrade();

		assertThat(schemaEntry(done, this.a.code()).get("state").asString()).isEqualTo("UP_TO_DATE");
		assertThat(schemaEntry(done, this.b.code()).get("state").asString()).isEqualTo("UP_TO_DATE");
		this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token()))).andExpect(status().isOk());
	}

	@Test
	void aCompanyIsRefusedWhileItsDatabaseIsUpgradedAndRestoredAfterwards() throws Exception {
		TenantRef refA = new TenantRef(this.a.id(), this.a.code());
		try {
			this.schemas.upgrading(refA, 2);
			// Requête authentifiée et connexion : 503 explicite, l'autre entreprise n'est pas concernée.
			this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token())))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SCHEMA_UPGRADE_IN_PROGRESS"));
			login(this.a.code(), "admin", ADMIN_PASSWORD).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SCHEMA_UPGRADE_IN_PROGRESS"));
			this.mvc.perform(get("/api/auth/me").with(bearer(this.b.token()))).andExpect(status().isOk());

			this.schemas.failed(refA, 2, "échec simulé");
			this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token())))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SCHEMA_UPGRADE_FAILED"));

			this.schemas.upToDate(refA, 2, 2);
			this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token()))).andExpect(status().isOk());
		}
		finally {
			this.schemas.upToDate(refA, 0, 0);
		}
	}

	@Test
	void aFailedUpgradeKeepsTheCompanyClosedUntilARetrySucceeds() throws Exception {
		org.mockito.ArgumentMatcher<TenantRef> companyA = tenant -> tenant != null && tenant.companyId().equals(this.a.id());
		try {
			// Migrations en attente détectées, puis échec de leur application pour l'entreprise A seulement.
			org.mockito.Mockito.doReturn(2).when(this.migrations).pendingCount(argThat(companyA), any());
			doThrow(new IllegalStateException("Migration simulée en échec")).when(this.migrations)
				.migrate(argThat(companyA), any());
			this.mvc.perform(post("/api/platform/schema-upgrades").with(bearer(this.superToken)))
				.andExpect(status().isAccepted());
			JsonNode failed = awaitSchemaUpgrade();

			JsonNode entryA = schemaEntry(failed, this.a.code());
			assertThat(entryA.get("state").asString()).isEqualTo("FAILED");
			assertThat(entryA.get("pending").asInt()).isEqualTo(2);
			assertThat(entryA.get("message").asString()).contains("Migration simulée en échec");
			assertThat(schemaEntry(failed, this.b.code()).get("state").asString()).isEqualTo("UP_TO_DATE");
			this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token())))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SCHEMA_UPGRADE_FAILED"));

			// Cause corrigée : relance par le Super Admin, entreprise rouverte.
			doCallRealMethod().when(this.migrations).migrate(any(), any());
			doCallRealMethod().when(this.migrations).pendingCount(any(), any());
			this.mvc.perform(post("/api/platform/schema-upgrades").with(bearer(this.superToken)))
				.andExpect(status().isAccepted());
			assertThat(schemaEntry(awaitSchemaUpgrade(), this.a.code()).get("state").asString()).isEqualTo("UP_TO_DATE");
			this.mvc.perform(get("/api/auth/me").with(bearer(this.a.token()))).andExpect(status().isOk());
		}
		finally {
			doCallRealMethod().when(this.migrations).migrate(any(), any());
			doCallRealMethod().when(this.migrations).pendingCount(any(), any());
			this.schemas.upToDate(new TenantRef(this.a.id(), this.a.code()), 0, 0);
		}
	}

	// --- Outils -------------------------------------------------------------------------------------

	/** Attend la fin de la mise à jour en arrière-plan (état renvoyé au Super Admin). */
	private JsonNode awaitSchemaUpgrade() throws Exception {
		long deadline = System.currentTimeMillis() + 60_000;
		while (true) {
			JsonNode status = read(this.mvc.perform(get("/api/platform/schema-upgrades").with(bearer(this.superToken)))
				.andExpect(status().isOk()));
			if (!status.get("running").asBoolean()) {
				return status;
			}
			assertThat(System.currentTimeMillis()).as("mise à jour des bases terminée en 60 s").isLessThan(deadline);
			Thread.sleep(100);
		}
	}

	private static JsonNode schemaEntry(JsonNode status, String code) {
		for (JsonNode entry : status.get("companies")) {
			if (code.equals(entry.get("code").asString())) {
				return entry;
			}
		}
		throw new AssertionError("Base de l'entreprise « " + code + " » non vérifiée");
	}

	private Company provision(String code, String name, String color) throws Exception {
		JsonNode created = read(this.mvc.perform(post("/api/platform/companies").with(bearer(this.superToken))
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "legalName": "%s SARL", "tradeName": "%s", "ownerLastName": "Kouassi",
					 "adminUsername": "admin", "primaryColor": "%s", "city": "Abidjan"}""".formatted(code, name, name, color)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ACTIVE")));
		UUID id = UUID.fromString(created.get("id").asString());
		this.createdCompanies.add(id);
		JsonNode link = read(this.mvc.perform(post("/api/platform/companies/{id}/admin-access-link", id)
			.with(bearer(this.superToken))).andExpect(status().isOk()));
		// Avant activation : aucun mot de passe, connexion impossible.
		login(code, "admin", ADMIN_PASSWORD).andExpect(status().isUnauthorized());
		activate(code, link.get("token").asString(), ADMIN_PASSWORD).andExpect(status().isNoContent());
		JsonNode session = read(login(code, "admin", ADMIN_PASSWORD).andExpect(status().isOk())
			.andExpect(jsonPath("$.user.company.code").value(code)));
		return new Company(id, code, session.get("accessToken").asString(), session.get("refreshToken").asString());
	}

	private ResultActions createCompany(String code, String key) throws Exception {
		var request = post("/api/platform/companies").with(bearer(this.superToken)).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "legalName": "Idem SARL", "tradeName": "Idem", "ownerLastName": "Traoré", "adminUsername": "admin"}"""
				.formatted(code));
		if (key != null) {
			request = request.header("Idempotency-Key", key);
		}
		return this.mvc.perform(request);
	}

	private ResultActions login(String company, String username, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("company", company, "username", username, "password", password))));
	}

	private ResultActions activate(String company, String token, String password) throws Exception {
		return this.mvc.perform(post("/api/auth/activate").contentType(MediaType.APPLICATION_JSON)
			.content(this.json.writeValueAsString(Map.of("company", company, "token", token, "newPassword", password))));
	}

	private static String branding(String primary) {
		return """
				{"primaryColor": "%s", "secondaryColor": "#7C3AED", "accentColor": "#16A34A", "sidebarColor": "#0B1F3A",
				 "headerColor": "#FFFFFF", "buttonColor": "#2563EB", "linkColor": "#2563EB", "themeMode": "LIGHT",
				 "highContrast": false}""".formatted(primary);
	}

	/** PNG 1 × 1 valide. */
	private static byte[] tinyPng() throws Exception {
		var image = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
		var out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private String sign(UUID subject, UUID companyId) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("geststock")
			.subject(subject.toString())
			.issuedAt(now)
			.expiresAt(now.plusSeconds(600))
			.claim(JwtTokenService.TYPE_CLAIM, JwtTokenService.TENANT_TYPE)
			.claim(JwtTokenService.TENANT_CLAIM, companyId.toString())
			.build();
		return this.jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	private String signLegacy(UUID subject) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("geststock").subject(subject.toString()).issuedAt(now)
			.expiresAt(now.plusSeconds(600)).build();
		return this.jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor bearer(String token) {
		return request -> {
			request.addHeader("Authorization", "Bearer " + token);
			return request;
		};
	}

	private JsonNode read(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private record Company(UUID id, String code, String token, String refreshToken) {
	}

}
