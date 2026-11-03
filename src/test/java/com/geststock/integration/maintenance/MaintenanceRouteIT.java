package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.geststock.integration.link.IntegrationTokenService;
import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Route interne de maintenance (G20a ; G20-D1, D2, D4, D11, D12, D14 ; plan § 33) : chaîne {@code /internal/**}
 * dédiée, secret du cron comparé à son empreinte (deux empreintes pendant une rotation), aucun jeton utilisateur ni de
 * liaison accepté, aucune écriture en base sur un échec d'authentification (compteur en mémoire, journaux à paliers),
 * bail unique ({@code 409 MAINTENANCE_IN_PROGRESS}), rapport agrégé sans identifiant, sans CORS ni cache. Route sans
 * travail à ce stade (G20b et suivants).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@TestPropertySource(properties = { "geststock.integration.maintenance.enabled=true",
		"geststock.integration.maintenance.token-sha256=" + MaintenanceRouteIT.CURRENT_SHA256,
		"geststock.integration.maintenance.previous-token-sha256=" + MaintenanceRouteIT.PREVIOUS_SHA256,
		// Limite des appels authentifiés relevée : vérifiée par MaintenanceRateLimitIT.
		"geststock.integration.maintenance.calls-per-window=100000" })
class MaintenanceRouteIT {

	static final String ROUTE = "/internal/integration/maintenance";

	/** Secrets publics de test, sans valeur hors des tests automatisés. */
	static final String CURRENT = "test-maintenance-token-current-not-a-secret-0123456789";

	static final String PREVIOUS = "test-maintenance-token-previous-not-a-secret-0123456789";

	static final String CURRENT_SHA256 = "aa30cfbcb57ceb7df60ec3ed045c6fded70fb3635ca26f72edd1018ecd4bc112";

	static final String PREVIOUS_SHA256 = "61784f8f92226e94aa904bddd26298b9b4cf607043ee52c55628ebe973132a63";

	private static final String SUPER_ADMIN = "it_g20_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final String USER = "it_g20_admin";

	private static final String USER_PASSWORD = "Maintenance2026";

	/** Loggers que la configuration limite parce qu'ils citent les valeurs reçues (G12, G15, revue G16). */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private IntegrationTokenService linkTokens;

	@Autowired
	private MaintenanceLease lease;

	@Autowired
	private MaintenanceAuthFailures authFailures;

	@Autowired
	private JsonMapper json;

	private final List<String> sensitive = Collections.synchronizedList(new ArrayList<>(List.of(CURRENT, PREVIOUS,
			CURRENT_SHA256, PREVIOUS_SHA256)));

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void captureLogs() {
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		for (Logger logger : context.getLoggerList()) {
			if (logger.getLevel() != null && !CAPPED_LOGGERS.contains(logger.getName())) {
				this.levels.put(logger, logger.getLevel());
				logger.setLevel(Level.TRACE);
			}
		}
		this.logs = new ListAppender<>();
		this.logs.start();
		context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(this.logs);
	}

	@AfterEach
	void checkLogs() {
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this.logs);
		this.levels.forEach(Logger::setLevel);
		this.levels.clear();
		try {
			// Spring Test journalise en TRACE la clé de son cache de contextes, qui contient les propriétés de test
			// (dont l'empreinte de test) : journal du seul outillage de test, absent en production.
			List<String> messages = List.copyOf(this.logs.list).stream()
				.filter(event -> !event.getLoggerName().startsWith("org.springframework.test."))
				.map(MaintenanceRouteIT::render)
				.toList();
			assertThat(messages).as("aucun secret ni jeton dans les journaux")
				.allSatisfy(message -> List.copyOf(this.sensitive).forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
			this.jdbc.update("delete from user_role where user_id in (select id from app_user where username = ?)", USER);
			this.jdbc.update("delete from refresh_token where user_id in (select id from app_user where username = ?)", USER);
			this.jdbc.update("delete from app_user where username = ?", USER);
		}
	}

	// --- Authentification (G20-D1, G20-D2) --------------------------------------------------------------------

	@Test
	void withoutTheCronSecretTheRouteIsUnauthorizedAndNoPassStarts() throws Exception {
		long generation = generation();
		String linkToken = remember(this.linkTokens.issue(UUID.randomUUID(), "client-g20", Set.of("catalog:read")).value());
		List<String> authorizations = new ArrayList<>(List.of("Bearer " + remember("faux-secret-" + UUID.randomUUID()),
				"Bearer ", "Bearer " + CURRENT + "x", "bearer-" + CURRENT, CURRENT,
				"Basic " + Base64.getEncoder().encodeToString(("cron:" + CURRENT).getBytes(StandardCharsets.UTF_8)),
				"Bearer " + CURRENT_SHA256, "Bearer " + linkToken, "Bearer " + platformAdminToken(),
				"Bearer " + companyUserToken()));
		for (String authorization : authorizations) {
			unauthorized(this.mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, authorization)));
		}
		unauthorized(this.mvc.perform(post(ROUTE)));
		assertThat(generation()).as("aucun passage commencé").isEqualTo(generation);
	}

	@Test
	void bothFingerprintsAreAcceptedDuringARotation() throws Exception {
		call(CURRENT).andExpect(status().isOk());
		call(PREVIOUS).andExpect(status().isOk());
		// Le schéma est insensible à la casse (RFC 9110 § 11.1).
		this.mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, "bearer " + CURRENT)).andExpect(status().isOk());
	}

	@Test
	void otherMethodsAndPathsUnderInternalAreNotFound() throws Exception {
		this.mvc.perform(get(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + CURRENT))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
		this.mvc.perform(post("/internal/integration/purge").header(HttpHeaders.AUTHORIZATION, "Bearer " + CURRENT))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void theInternalRouteHasNoCors() throws Exception {
		this.mvc.perform(options(ROUTE).header(HttpHeaders.ORIGIN, "http://localhost:4200")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		this.mvc.perform(post(ROUTE).header(HttpHeaders.ORIGIN, "http://localhost:4200")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + CURRENT))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	// --- Rapport (G20-D11) et bail (G20-D4, G20-D12) ----------------------------------------------------------------

	@Test
	void aPassReturnsAnAggregatedReportWithoutIdentifiersAndReleasesTheLease() throws Exception {
		call(CURRENT).andExpect(status().isOk());
		for (int i = 0; i < 3; i++) {
			unauthorized(this.mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + remember("faux-" + UUID.randomUUID()))));
		}
		long before = generation();

		JsonNode report = read(call(CURRENT).andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString(MediaType.APPLICATION_JSON_VALUE)))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store"))));
		assertThat(report.propertyNames()).containsExactlyInAnyOrder("status", "mode", "generation", "durationMs",
				"companies", "purge", "links", "alerts", "authFailures");
		assertThat(report.get("companies").propertyNames()).containsExactlyInAnyOrder("active", "visited", "skippedUpgrade",
				"errors", "interrupted", "remaining");
		assertThat(report.get("purge").propertyNames()).containsExactlyInAnyOrder("eligible", "deleted", "batches", "blocked",
				"maxLockWaitMs");
		assertThat(report.get("links").propertyNames()).containsExactlyInAnyOrder("active", "suspended");
		assertThat(report.get("alerts").isArray()).isTrue();
		assertThat(report.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(report.get("mode").asString()).as("purge en simulation (G20-D5)").isEqualTo("SIMULATION");
		assertThat(report.get("generation").asLong()).isEqualTo(before + 1);
		assertThat(report.get("durationMs").asLong()).isNotNegative();
		assertThat(report.get("authFailures").get("count").asLong()).as("échecs depuis le rapport précédent").isEqualTo(3);
		assertThat(report.get("authFailures").get("scope").asString()).isEqualTo("INSTANCE");

		assertThat(leaseReleased()).as("bail libéré en fin de passage").isTrue();
	}

	@Test
	void aPassInProgressGivesAConflictAndStartsNothing() throws Exception {
		MaintenanceLease.Lease held = this.lease.acquire(java.time.Duration.ofSeconds(50)).orElseThrow();
		try {
			call(CURRENT).andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("MAINTENANCE_IN_PROGRESS"))
				.andExpect(jsonPath("$.path").value(ROUTE));
			assertThat(this.lease.isHeld(held)).as("bail du passage en cours intact").isTrue();
			assertThat(generation()).isEqualTo(held.generation());
		}
		finally {
			this.lease.release(held);
		}
		call(CURRENT).andExpect(status().isOk());
	}

	// --- Simulation par défaut (G20-D5) ----------------------------------------------------------------------------

	@Test
	void byDefaultAPassSimulatesThePurgeWithoutDeletingNorMovingTheWatermark() throws Exception {
		resetLegacyFeed();
		try {
			for (int i = 0; i < 5; i++) {
				this.jdbc.update("""
						insert into integration_outbox (type, operation, object_id, object_version, created_at)
						values ('CATEGORY', 'UPSERT', ?, 1, now() - interval '100 days')""", UUID.randomUUID());
			}
			JsonNode report = read(call(CURRENT).andExpect(status().isOk()));
			assertThat(report.get("mode").asString()).isEqualTo("SIMULATION");
			assertThat(report.get("purge").get("eligible").asLong()).as("événements éligibles comptés").isGreaterThanOrEqualTo(4);
			assertThat(report.get("purge").get("deleted").asLong()).isZero();
			assertThat(this.jdbc.queryForObject("select count(*) from integration_outbox", Integer.class))
				.as("aucune suppression").isEqualTo(5);
			assertThat(this.jdbc.queryForObject("select purged_through_seq from integration_feed_state", Long.class))
				.as("repère inchangé").isZero();
		}
		finally {
			resetLegacyFeed();
		}
	}

	// --- Échecs d'authentification (G20-D14) ---------------------------------------------------------------------

	@Test
	void aThousandConcurrentFailuresWriteNothingAndTheCronStillPasses() throws Exception {
		Logger failuresLogger = (Logger) LoggerFactory.getLogger(MaintenanceAuthFailures.class);
		long failuresBefore = this.authFailures.total();
		Map<String, Long> before = platformWrites();

		ExecutorService pool = Executors.newFixedThreadPool(32);
		try {
			List<Future<Integer>> burst = new ArrayList<>();
			for (int i = 0; i < 1000; i++) {
				String wrong = "faux-secret-rafale-" + i;
				burst.add(pool.submit(() -> this.mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + wrong))
					.andReturn().getResponse().getStatus()));
			}
			// Le vrai cron passe pendant la rafale : aucune connexion à la base n'est prise par un échec.
			call(CURRENT).andExpect(status().isOk());
			for (Future<Integer> status : burst) {
				assertThat(status.get(60, TimeUnit.SECONDS)).isEqualTo(401);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(this.authFailures.total() - failuresBefore).as("échecs comptés en mémoire").isEqualTo(1000);
		Map<String, Long> after = platformWrites();
		assertThat(after.get("audit")).as("aucune ligne d'audit par échec").isEqualTo(before.get("audit"));
		assertThat(after.get("otherCounters")).as("aucun compteur de débit pour un échec").isEqualTo(before.get("otherCounters"));
		assertThat(after.get("maintenanceHits") - before.get("maintenanceHits")).as("seul l'appel authentifié est compté")
			.isEqualTo(1);
		List<ILoggingEvent> warnings = List.copyOf(this.logs.list).stream()
			.filter(event -> event.getLoggerName().equals(failuresLogger.getName()) && event.getLevel() == Level.WARN)
			.toList();
		assertThat(warnings).as("journaux à paliers (1, 10, 100, 1 000…)").isNotEmpty().hasSizeLessThanOrEqualTo(4);
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	/** Flux de l'entreprise historique vidé, repère et dernier seq à zéro, acquittements effacés. */
	private void resetLegacyFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("delete from integration_link_ack");
		this.jdbc.update("""
				insert into integration_feed_state (id) values ('00000000-0000-0000-0000-000000000001'::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""");
	}

	private ResultActions call(String secret) throws Exception {
		return this.mvc.perform(post(ROUTE).header(HttpHeaders.AUTHORIZATION, "Bearer " + secret));
	}

	private static void unauthorized(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
			.andExpect(jsonPath("$.path").value(ROUTE));
	}

	private long generation() {
		return this.platform.jdbc().sql("select generation from platform.integration_maintenance_lease").query(Long.class).single();
	}

	private boolean leaseReleased() {
		return this.platform.jdbc().sql("select released_at is not null from platform.integration_maintenance_lease")
			.query(Boolean.class).single();
	}

	/** Écritures de la base plateforme qu'un échec d'authentification pourrait produire. */
	private Map<String, Long> platformWrites() {
		Map<String, Long> writes = new LinkedHashMap<>();
		writes.put("audit", this.platform.jdbc().sql("select count(*) from platform.integration_audit_log").query(Long.class).single());
		writes.put("otherCounters", this.platform.jdbc()
			.sql("select count(*) + coalesce(sum(hits), 0) from platform.integration_rate_limit where counter_key <> :key")
			.param("key", MaintenanceController.CALLS_COUNTER).query(Long.class).single());
		writes.put("maintenanceHits", this.platform.jdbc()
			.sql("select coalesce(sum(hits), 0) from platform.integration_rate_limit where counter_key = :key")
			.param("key", MaintenanceController.CALLS_COUNTER).query(Long.class).single());
		return writes;
	}

	private String platformAdminToken() throws Exception {
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", SUPER_ADMIN).param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).update();
		return remember(read(this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
			.andExpect(status().isOk())).get("accessToken").asString());
	}

	private String companyUserToken() throws Exception {
		this.jdbc.update("delete from app_user where username = ?", USER);
		UUID user = this.jdbc.queryForObject("""
				insert into app_user (username, last_name, must_change_password, password_hash)
				values (?, 'Essai maintenance', false, ?) returning id""", UUID.class, USER, this.passwordEncoder.encode(USER_PASSWORD));
		this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = 'ADMIN'", user);
		MockHttpServletRequestBuilder login = post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + USER + "\", \"password\": \"" + USER_PASSWORD + "\"}");
		return remember(read(this.mvc.perform(login).andExpect(status().isOk())).get("accessToken").asString());
	}

	private JsonNode read(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private String remember(String value) {
		this.sensitive.add(value);
		return value;
	}

	private static String render(ILoggingEvent event) {
		StringBuilder text = new StringBuilder(event.getFormattedMessage());
		for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
			text.append('\n').append(proxy.getClassName()).append(": ").append(proxy.getMessage());
		}
		return text.toString();
	}

}
