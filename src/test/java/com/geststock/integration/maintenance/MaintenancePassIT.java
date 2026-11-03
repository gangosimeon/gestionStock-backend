package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.geststock.integration.PassControl;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;

/**
 * Orchestration d'un passage de maintenance à travers la route (lot 3, G20b ; G20-D3, D9, D12, D13 ; décisions D, E, F ;
 * plan § 33.3) : trois entreprises réelles, A (historique), B et C (provisionnées pour ce test), purge en {@code DELETE}
 * sur les bases de test, horloge du budget manuelle.
 *
 * <ul>
 * <li>Budget : une entreprise ne dépasse pas la moitié du budget restant, sauf la dernière à visiter (l'unique compris) ;
 * budget global épuisé : statut {@code PARTIAL}, entreprises restantes comptées et visitées aux passages suivants.</li>
 * <li>Ordre : interrompue une fois, en tête ; deux fois, file normale ; comptée dans {@code interrupted}.</li>
 * <li>Une erreur ou une migration ne bloque pas les autres entreprises ; le message d'une erreur n'apparaît ni dans la
 * réponse ni dans les journaux.</li>
 * <li>Bail repris pendant une purge : l'ancien passage ne commence aucun lot de plus, ne termine pas l'entreprise et ne
 * touche pas au bail du nouveau propriétaire ; statut {@code FAILED}, jamais confondu avec un budget épuisé.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MaintenancePassIT extends DeleteModeMaintenanceSupport {

	private static final String SUPER_ADMIN = "it_g20b_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	@Autowired
	private MaintenanceCompanies companies;

	@Autowired
	private TenantSchemaRegistry schemas;

	@Autowired
	private TenantDataSourceRegistry pools;

	@Autowired
	private ProvisionerAccess provisioner;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private final List<UUID> provisioned = new ArrayList<>();

	private TenantRef a;

	private TenantRef b;

	private TenantRef c;

	@BeforeAll
	void provisionCompanies() throws Exception {
		this.a = legacy();
		this.b = provisionCompany();
		this.c = provisionCompany();
	}

	@AfterAll
	void dropCompanies() throws Exception {
		for (UUID id : this.provisioned) {
			this.pools.close(id);
			var database = this.platform.jdbc()
				.sql("select database_name, runtime_role from platform.company_database where company_id = :id and mode = 'DEDICATED'")
				.param("id", id).query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).optional();
			if (database.isPresent()) {
				try (Connection connection = this.provisioner.server().getConnection();
						Statement statement = connection.createStatement()) {
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

	@BeforeEach
	void setUp() {
		resetLegacyFeed();
		forgetMaintenanceState(List.of(this.a.companyId(), this.b.companyId(), this.c.companyId()));
		assertThat(this.retainedLinks.of(this.a.companyId())).as("entreprise historique sans liaison").isEmpty();
	}

	@AfterEach
	void tearDown() {
		resetLegacyFeed();
		this.schemas.upToDate(this.b, 0, 0);
	}

	// --- Budget (G20-D3, décision D) -----------------------------------------------------------------------------

	@Test
	void aSingleCompanyMayUseTheWholeRemainingBudget() throws Exception {
		doReturn(List.of(this.a)).when(this.tenants).active();
		oldUnsequencedEvents(5);
		// 60 % du budget consommés avant la purge : au-delà de la moitié, mais l'entreprise est la seule à visiter.
		advanceBeforePurge(this.a, 0.6);

		JsonNode report = pass();
		assertThat(report.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(report.get("purge").get("deleted").asLong()).as("toute la borne (le plus grand seq conservé)").isEqualTo(4);
		assertThat(legacySeqs()).containsExactly(5L);
		assertCompanies(report, 1, 1, 0, 0, 0, 0);
		assertThat(outcome(this.a)).isEqualTo("OK");
	}

	@Test
	void theFirstOfTwoCompaniesIsLimitedToHalfTheRemainingBudget() throws Exception {
		doReturn(List.of(this.a, this.b)).when(this.tenants).active();
		oldUnsequencedEvents(5);
		advanceBeforePurge(this.a, 0.6);

		JsonNode report = pass();
		assertThat(report.get("status").asString()).as("entreprise terminée partiellement").isEqualTo("PARTIAL");
		assertThat(report.get("purge").get("deleted").asLong()).as("premier lot seulement").isEqualTo(1);
		assertThat(legacyPurgedThrough()).isEqualTo(1);
		assertThat(outcome(this.a)).isEqualTo("PARTIAL");
		assertThat(outcome(this.b)).as("la dernière dispose du reste du budget").isEqualTo("OK");
		assertCompanies(report, 2, 2, 0, 0, 0, 0);
	}

	@Test
	void withABudgetForOneCompanyEachCompanyIsVisitedInThreePasses() throws Exception {
		doReturn(List.of(this.a, this.b, this.c)).when(this.tenants).active();
		List<UUID> visits = new ArrayList<>();
		doAnswer(invocation -> {
			visits.add(TenantContext.require().companyId());
			this.ticker.advance(this.properties.budget());
			return invocation.callRealMethod();
		}).when(this.feed).purge(any());

		for (int pass = 1; pass <= 3; pass++) {
			JsonNode report = pass();
			assertThat(report.get("status").asString()).as("passage %d : entreprises restantes", pass).isEqualTo("PARTIAL");
			assertCompanies(report, 3, 1, 0, 0, 0, 2);
		}
		assertThat(visits).containsExactly(this.a.companyId(), this.b.companyId(), this.c.companyId());
	}

	// --- Ordre et interruptions (G20-D13, décision F) -------------------------------------------------------------

	@Test
	void aCompanyInterruptedOnceIsVisitedFirstAndCounted() throws Exception {
		doReturn(List.of(this.a, this.b, this.c)).when(this.tenants).active();
		visitAll();
		this.companies.markStarted(this.c.companyId(), 900_001);
		AtomicReference<UUID> first = budgetForOneCompany();

		JsonNode report = pass();
		assertThat(first.get()).as("reprise rapide").isEqualTo(this.c.companyId());
		assertCompanies(report, 3, 1, 0, 0, 1, 2);
	}

	@Test
	void aCompanyInterruptedTwiceJoinsTheNormalQueue() throws Exception {
		doReturn(List.of(this.a, this.b, this.c)).when(this.tenants).active();
		visitAll();
		this.companies.markStarted(this.c.companyId(), 900_002);
		this.companies.markStarted(this.c.companyId(), 900_003);
		AtomicReference<UUID> first = budgetForOneCompany();

		JsonNode report = pass();
		assertThat(first.get()).as("tentative la plus ancienne").isEqualTo(this.a.companyId());
		assertCompanies(report, 3, 1, 0, 0, 1, 2);
	}

	@Test
	void anErrorInOneCompanyDoesNotBlockTheOthersAndLeaksNoMessage() throws Exception {
		doReturn(List.of(this.a, this.b, this.c)).when(this.tenants).active();
		String marker = "marqueur-erreur-" + UUID.randomUUID();
		doAnswer(invocation -> {
			if (TenantContext.require().equals(this.b)) {
				throw new IllegalStateException(marker);
			}
			return invocation.callRealMethod();
		}).when(this.feed).purge(any());
		ListAppender<ILoggingEvent> logs = captureLogs();
		try {
			String body = this.mvc.perform(post(MaintenanceRouteIT.ROUTE)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + MaintenanceRouteIT.CURRENT))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain(marker).doesNotContain("IllegalStateException");
			assertCompanies(this.json.readTree(body), 3, 3, 0, 1, 0, 0);
			assertThat(outcome(this.b)).isEqualTo("ERROR");
			assertThat(outcome(this.a)).isEqualTo("OK");
			assertThat(outcome(this.c)).isEqualTo("OK");
			assertThat(List.copyOf(logs.list)).as("message de l'erreur absent des journaux")
				.allSatisfy(event -> assertThat(render(event)).doesNotContain(marker));
		}
		finally {
			detach(logs);
		}
	}

	@Test
	void aCompanyWhoseSchemaIsUpgradingIsSkippedWithoutBlockingTheOthers() throws Exception {
		doReturn(List.of(this.a, this.b, this.c)).when(this.tenants).active();
		this.schemas.upgrading(this.b, 1);
		List<UUID> touched = new ArrayList<>();
		doAnswer(invocation -> {
			touched.add(TenantContext.require().companyId());
			return invocation.callRealMethod();
		}).when(this.feed).sequence(any());
		doAnswer(invocation -> {
			touched.add(TenantContext.require().companyId());
			return invocation.callRealMethod();
		}).when(this.feed).purge(any());

		JsonNode report = pass();
		assertCompanies(report, 3, 3, 1, 0, 0, 0);
		assertThat(outcome(this.b)).isEqualTo("SKIPPED_UPGRADE");
		assertThat(touched).as("base en migration jamais touchée").doesNotContain(this.b.companyId())
			.contains(this.a.companyId(), this.c.companyId());
	}

	// --- Bail repris pendant une purge (G20-D12, précision 1) -------------------------------------------------------

	@Test
	void aLeaseTakenOverDuringThePurgeStopsTheOldPassBeforeItsNextBatch() throws Exception {
		doReturn(List.of(this.a, this.b)).when(this.tenants).active();
		oldUnsequencedEvents(6);
		AtomicReference<MaintenanceLease.Lease> newer = new AtomicReference<>();
		// Dès que le premier lot est validé, le bail échoit (processus réputé arrêté) et un nouveau passage le prend.
		doAnswer(invocation -> {
			if (newer.get() == null && legacyPurgedThrough() >= 1) {
				this.platform.jdbc().sql("""
						update platform.integration_maintenance_lease
						set acquired_at = clock_timestamp() - interval '2 minutes', expires_at = clock_timestamp() - interval '1 second'
						where released_at is null""").update();
				newer.set(this.lease.acquire(Duration.ofSeconds(50)).orElseThrow());
			}
			return invocation.callRealMethod();
		}).when(this.lease).isHeld(any());

		try {
			JsonNode report = pass();
			assertThat(report.get("status").asString()).as("bail perdu, pas un budget épuisé").isEqualTo("FAILED");
			assertThat(report.get("purge").get("deleted").asLong()).as("aucun lot après la perte du bail").isEqualTo(1);
			assertThat(legacyPurgedThrough()).isEqualTo(1);
			assertThat(legacySeqs()).containsExactly(2L, 3L, 4L, 5L, 6L);
			assertThat(this.lease.isHeld(newer.get())).as("bail du nouveau propriétaire ni libéré ni prolongé par l'ancien")
				.isTrue();
			assertThat(outcome(this.a)).as("entreprise non terminée par l'ancien passage").isNull();
			assertThat(startedGeneration(this.b)).as("aucune entreprise commencée après la perte").isNull();
		}
		finally {
			if (newer.get() != null) {
				this.lease.release(newer.get());
			}
		}
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	/** Avance l'horloge du budget de {@code share} du budget juste avant la purge de {@code company}. */
	private void advanceBeforePurge(TenantRef company, double share) {
		doAnswer(invocation -> {
			if (TenantContext.require().equals(company)) {
				this.ticker.advance(Duration.ofNanos((long) (this.properties.budget().toNanos() * share)));
			}
			return invocation.callRealMethod();
		}).when(this.feed).purge(any(PassControl.class));
	}

	/** Budget épuisé par la première entreprise visitée ; renvoie son identifiant. */
	private AtomicReference<UUID> budgetForOneCompany() {
		AtomicReference<UUID> first = new AtomicReference<>();
		doAnswer(invocation -> {
			first.compareAndSet(null, TenantContext.require().companyId());
			this.ticker.advance(this.properties.budget());
			return invocation.callRealMethod();
		}).when(this.feed).purge(any());
		return first;
	}

	/** A, B puis C visitées et terminées, dans cet ordre (générations de test). */
	private void visitAll() {
		long generation = 800_000;
		for (TenantRef company : List.of(this.a, this.b, this.c)) {
			this.companies.markStarted(company.companyId(), ++generation);
			this.companies.markCompleted(company.companyId(), generation, MaintenanceCompanies.Outcome.OK);
		}
	}

	private String outcome(TenantRef company) {
		return this.platform.jdbc().sql("select last_outcome from platform.integration_maintenance_company where company_id = :id")
			.param("id", company.companyId()).query(String.class).optional().orElse(null);
	}

	private Long startedGeneration(TenantRef company) {
		return this.platform.jdbc()
			.sql("select last_started_generation from platform.integration_maintenance_company where company_id = :id")
			.param("id", company.companyId()).query(Long.class).optional().orElse(null);
	}

	private ListAppender<ILoggingEvent> captureLogs() {
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logs);
		return logs;
	}

	private static void detach(ListAppender<ILoggingEvent> logs) {
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logs);
	}

	private static String render(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(event.getFormattedMessage());
		for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
			text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
		}
		return text.toString();
	}

	/** Entreprise ACTIVE réellement provisionnée (base dédiée), par l'API de la plateforme. */
	private TenantRef provisionCompany() throws Exception {
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", SUPER_ADMIN).param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).update();
		String login = this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String superToken = this.json.readTree(login).get("accessToken").asString();
		String code = "it-g20b-" + UUID.randomUUID().toString().substring(0, 8);
		String created = this.mvc.perform(post("/api/platform/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + superToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "legalName": "Commerce G20b SARL", "tradeName": "Commerce G20b", "ownerLastName": "Kouassi",
					 "adminUsername": "admin"}""".formatted(code)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(this.json.readTree(created).get("id").asString());
		this.provisioned.add(id);
		return new TenantRef(id, code);
	}

}
