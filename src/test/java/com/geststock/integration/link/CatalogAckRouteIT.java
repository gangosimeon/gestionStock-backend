package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

import com.geststock.integration.AckFloor;
import com.geststock.integration.AckSource;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /catalog/changes/ack} (lot 3, étape G17 ; plan § 30.3, v17-D3, v17-D6, v17-D7, v17-D12 à v17-D14) avec
 * deux entreprises réelles : A (l'entreprise de test historique) et B (provisionnée pour ce test, base dédiée).
 * Acquittement par liaison, dans la base de l'entreprise de la liaison, jamais en recul ; {@code seq} strict ; limite
 * de 60 appels par minute et par liaison, appliquée avant la lecture du corps ; aucune valeur reçue dans les journaux.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogAckRouteIT {

	private static final String CHANGES = "/api/integration/v1/catalog/changes";

	private static final String ACK = "/api/integration/v1/catalog/changes/ack";

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String SUPER_ADMIN = "it_g17_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final Set<String> CATALOG = Set.of("catalog:read");

	/** Loggers que la configuration limite parce qu'ils citent les valeurs reçues (G12, G15, revue G16). */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TenantDataSourceRegistry pools;

	@Autowired
	private ProvisionerAccess provisioner;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JsonMapper json;

	@Autowired
	private AckSource acknowledgements;

	private TestLinks links;

	private TenantRef companyA;

	private TenantRef companyB;

	private final List<UUID> createdCompanies = new ArrayList<>();

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeAll
	void provisionCompanies() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		this.companyA = new TenantRef(this.links.legacyCompany(), "test");
		this.companyB = provisionCompanyB();
	}

	@BeforeEach
	void setUp() {
		reset(this.companyA);
		reset(this.companyB);
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
	void tearDown() {
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(this.logs);
		this.levels.forEach(Logger::setLevel);
		this.levels.clear();
		try {
			List<String> messages = this.logs.list.stream().map(CatalogAckRouteIT::render).toList();
			assertThat(messages).as("aucune valeur reçue dans les journaux")
				.allSatisfy(message -> this.sensitive.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			this.links.cleanUp();
			reset(this.companyA);
			reset(this.companyB);
		}
	}

	@AfterAll
	void dropCompanyB() throws Exception {
		for (UUID id : this.createdCompanies) {
			this.pools.close(id);
			var database = this.platform.jdbc()
				.sql("select database_name, runtime_role from platform.company_database where company_id = :id and mode = 'DEDICATED'")
				.param("id", id).query((rs, n) -> new String[] { rs.getString(1), rs.getString(2) }).optional();
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

	// --- Acquittement ----------------------------------------------------------------------------------------

	@Test
	void theAcknowledgementGoesUpNeverBackAndAReplayGivesTheSameAnswer() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 5);

		ack(linked, "{\"seq\": 3}").andExpect(status().isOk())
			.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
			.andExpect(jsonPath("$.acknowledgedSeq").value(3));
		ack(linked, "{\"seq\": 5}").andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedSeq").value(5));
		ack(linked, "{\"seq\": 2}").andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedSeq").value(5));
		ack(linked, "{\"seq\": 5}").andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedSeq").value(5));
		ack(linked, "{\"seq\": 0}").andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedSeq").value(5));

		assertThat(stored(this.companyA, linked.id())).isEqualTo(5L);
	}

	@Test
	void anAcknowledgementBelowThePurgeMarkIsAcceptedAndTheNextReadOfTheFeedHasExpired() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 5);
		TenantContext.runAs(this.companyA, () -> {
			this.jdbc.update("delete from integration_outbox where seq <= 4");
			this.jdbc.update("update integration_feed_state set purged_through_seq = 4");
		});

		ack(linked, "{\"seq\": 1}").andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedSeq").value(1));
		assertThat(stored(this.companyA, linked.id())).isEqualTo(1L);
		bearer(get(CHANGES).param("after", "1"), linked.token(), this.mvc).andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
	}

	@Test
	void anAcknowledgementAheadOfTheFeedIsAConflictAndNothingIsStored() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 5);
		ack(linked, "{\"seq\": 4}").andExpect(status().isOk());

		ack(linked, "{\"seq\": 6}").andExpect(status().isConflict())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.code").value("CURSOR_AHEAD"));
		assertThat(stored(this.companyA, linked.id())).isEqualTo(4L);

		Linked fresh = active(this.companyA, CATALOG);
		ack(fresh, "{\"seq\": 9223372036854775807}").andExpect(status().isConflict());
		assertThat(stored(this.companyA, fresh.id())).as("aucune ligne").isNull();
	}

	// --- Corps strict (v17-D12) --------------------------------------------------------------------------------

	@Test
	void aMissingNullOrNegativeSeqIsAValidationFailureNamingTheField() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 2);

		for (String body : List.of("{}", "{\"seq\": null}", "{\"seq\": -1}", "{\"autre\": 1}")) {
			ack(linked, body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value("seq"));
		}
		assertThat(stored(this.companyA, linked.id())).isNull();
	}

	@Test
	void aSeqOfAnotherTypeOrAnUnreadableBodyIsMalformedWithoutImplicitConversion() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 2);

		for (String body : List.of("{\"seq\": \"1\"}", "{\"seq\": 1.5}", "{\"seq\": 1.0}", "{\"seq\": 1e0}", "{\"seq\": true}",
				"{\"seq\": {}}", "{\"seq\": [1]}", "{\"seq\": 9223372036854775808}", "", "   ", "[1]", "1", "\"seq\"",
				"pas du json", "{\"seq\": 1", "{\"seq\": 1, \"seq\": 2}", "{\"seq\": 1} 2")) {
			ack(linked, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
		}
		assertThat(stored(this.companyA, linked.id())).as("rien n'est enregistré").isNull();
	}

	@Test
	void aBodyOfMoreThanOneKilobyteIsTooLargeEvenIfValidAndUnknownFieldsAreIgnored() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 2);
		String prefix = "{\"seq\": 1, \"pad\": \"";
		String suffix = "\"}";
		String atLimit = prefix + "x".repeat(1024 - prefix.length() - suffix.length()) + suffix;
		String overLimit = prefix + "x".repeat(1025 - prefix.length() - suffix.length()) + suffix;
		assertThat(atLimit).hasSize(1024);
		assertThat(overLimit).hasSize(1025);

		// RFC 9110 § 15.5.14 : contenu plus grand que ce que le serveur accepte (comme FILE_TOO_LARGE pour les fichiers).
		ack(linked, overLimit).andExpect(status().isContentTooLarge())
			.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(jsonPath("$.code").value("CONTENT_TOO_LARGE"));
		assertThat(stored(this.companyA, linked.id())).as("rien n'est enregistré").isNull();

		ack(linked, atLimit).andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedSeq").value(1));
		ack(linked, "{\"seq\": 2, \"inconnu\": {\"a\": [1, 2]}}").andExpect(status().isOk())
			.andExpect(jsonPath("$.acknowledgedSeq").value(2));
	}

	@Test
	void aBodyOtherThanJsonIsAnUnsupportedMediaType() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 2);

		this.mvc.perform(post(ACK).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token())
			.contentType(MediaType.TEXT_PLAIN).content("{\"seq\": 1}"))
			.andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
		this.mvc.perform(post(ACK).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token()).content("{\"seq\": 1}"))
			.andExpect(status().isUnsupportedMediaType());
		this.mvc.perform(post(ACK).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token())
			.contentType("application/json;charset=UTF-8").content("{\"seq\": 1}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.acknowledgedSeq").value(1));
	}

	// --- Statuts, portée et isolation --------------------------------------------------------------------------

	@Test
	void aRevokedLinkIsUnauthorizedAndASuspendedLinkIsForbiddenButKeepsItsAcknowledgement() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 3);
		ack(linked, "{\"seq\": 2}").andExpect(status().isOk());

		this.registry.suspend(linked.id(), SuspensionReason.MANUAL);
		ack(linked, "{\"seq\": 3}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));
		assertThat(stored(this.companyA, linked.id())).isEqualTo(2L);

		this.registry.revoke(linked.id(), RevocationReason.SHOP_REQUEST);
		ack(linked, "{\"seq\": 3}").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("LINK_REVOKED"));
		assertThat(stored(this.companyA, linked.id())).isEqualTo(2L);

		Linked noScope = active(this.companyA, Set.of("orders:read"));
		ack(noScope, "{\"seq\": 1}").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
		this.mvc.perform(post(ACK).contentType(MediaType.APPLICATION_JSON).content("{\"seq\": 1}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void eachLinkWritesOnlyItsOwnAcknowledgementInTheDatabaseOfItsCompany() throws Exception {
		Linked ofA = active(this.companyA, CATALOG);
		Linked otherOfA = active(this.companyA, CATALOG);
		Linked ofB = active(this.companyB, CATALOG);
		sequencedEvents(this.companyA, ofA, 3);
		sequencedEvents(this.companyB, ofB, 7);

		ack(ofA, "{\"seq\": 3}").andExpect(status().isOk());
		// Curseur valable chez B (7) mais pas chez A (3) : l'entreprise est celle de la liaison.
		ack(ofA, "{\"seq\": 7}").andExpect(status().isConflict());
		ack(ofB, "{\"seq\": 7}").andExpect(status().isOk());

		assertThat(stored(this.companyA, ofA.id())).isEqualTo(3L);
		assertThat(stored(this.companyB, ofA.id())).as("rien chez B pour la liaison de A").isNull();
		assertThat(stored(this.companyB, ofB.id())).isEqualTo(7L);
		assertThat(stored(this.companyA, ofB.id())).as("rien chez A pour la liaison de B").isNull();
		assertThat(stored(this.companyA, otherOfA.id())).as("l'autre liaison de A n'est pas touchée").isNull();
		assertThat(TenantContext.current()).isEmpty();
	}

	@Test
	void thePurgeOfACompanyOnlyConsidersItsOwnLinksAndAcknowledgements() throws Exception {
		Linked ofA = active(this.companyA, CATALOG);
		Linked ofB = active(this.companyB, CATALOG);
		sequencedEvents(this.companyA, ofA, 3);
		sequencedEvents(this.companyB, ofB, 7);
		ack(ofB, "{\"seq\": 7}").andExpect(status().isOk());

		assertThat(TenantContext.callAs(this.companyA, this.acknowledgements::floor))
			.as("A : sa liaison n'a pas acquitté, l'acquittement de B ne compte pas").isEqualTo(AckFloor.blocked());
		assertThat(TenantContext.callAs(this.companyB, this.acknowledgements::floor))
			.as("B : sa seule liaison, acquittée").isEqualTo(AckFloor.acknowledged(7));
	}

	// --- Débit et concurrence ----------------------------------------------------------------------------------

	@Test
	void theSixtyFirstCallOfTheLinkIsRefusedBeforeReadingTheBodyAndAnotherLinkIsNotAffected() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		Linked other = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 2);
		awaitFreshWindow();

		for (int i = 0; i < 30; i++) {
			ack(linked, "illisible").andExpect(status().isBadRequest());
		}
		for (int i = 0; i < 30; i++) {
			ack(linked, "{\"seq\": 1}").andExpect(status().isOk());
		}
		ack(linked, "{\"seq\": 2}").andExpect(status().isTooManyRequests())
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER))
			.andExpect(jsonPath("$.code").value("RATE_LIMITED"));
		assertThat(stored(this.companyA, linked.id())).as("l'appel refusé n'est pas enregistré").isEqualTo(1L);

		ack(other, "{\"seq\": 2}").andExpect(status().isOk());
	}

	@Test
	void simultaneousAcknowledgementsOfOneLinkKeepTheLargest() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 20);
		int calls = 20;
		CyclicBarrier start = new CyclicBarrier(calls);
		ExecutorService pool = Executors.newFixedThreadPool(calls);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 1; i <= calls; i++) {
				String body = "{\"seq\": " + i + "}";
				results.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return ack(linked, body).andReturn().getResponse().getStatus();
				}));
			}
			for (Future<Integer> result : results) {
				assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(stored(this.companyA, linked.id())).isEqualTo(20L);
	}

	// --- Journaux ----------------------------------------------------------------------------------------------

	@Test
	void noReceivedValueReachesTheLogs() throws Exception {
		Linked linked = active(this.companyA, CATALOG);
		sequencedEvents(this.companyA, linked, 2);
		String marker = remember("g17marqueur" + UUID.randomUUID().toString().substring(0, 8));

		ack(linked, "{\"seq\": \"" + marker + "\"}").andExpect(status().isBadRequest());
		ack(linked, "{\"" + marker + "\": 1}").andExpect(status().isBadRequest());
		ack(linked, marker).andExpect(status().isBadRequest());
		ack(linked, "{\"seq\": 1, \"seq\": \"" + marker + "\"}").andExpect(status().isBadRequest());
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private record Linked(UUID id, String token) {
	}

	private Linked active(TenantRef company, Set<String> scopes) {
		Link link = this.links.active(this.links.client(), company.companyId(), "shop-g17-" + UUID.randomUUID(), scopes);
		String clientId = this.platform.jdbc().sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		return new Linked(link.id(), remember(this.tokens.issue(link.id(), clientId, scopes).value()));
	}

	private ResultActions ack(Linked linked, String body) throws Exception {
		return this.mvc.perform(post(ACK).header(HttpHeaders.AUTHORIZATION, "Bearer " + linked.token())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static ResultActions bearer(MockHttpServletRequestBuilder request, String token, MockMvc mvc) throws Exception {
		return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	/** {@code count} événements de l'entreprise, séquencés par une lecture du flux de la liaison. */
	private void sequencedEvents(TenantRef company, Linked linked, int count) throws Exception {
		TenantContext.runAs(company, () -> {
			for (int i = 0; i < count; i++) {
				this.jdbc.update("""
						insert into integration_outbox (type, operation, object_id, object_version, payload)
						values ('CATEGORY', 'UPSERT', ?, 1, '{}'::jsonb)""", UUID.randomUUID());
			}
		});
		bearer(get(CHANGES).param("after", "0").param("limit", "500"), linked.token(), this.mvc).andExpect(status().isOk());
	}

	private Long stored(TenantRef company, UUID link) {
		return TenantContext.callAs(company, () -> this.jdbc.query(
				"select acknowledged_seq from integration_link_ack where link_id = ?", (rs, n) -> rs.getLong(1), link)
			.stream().findFirst().orElse(null));
	}

	private void reset(TenantRef company) {
		TenantContext.runAs(company, () -> {
			this.jdbc.update("delete from integration_outbox");
			this.jdbc.update("""
					insert into integration_feed_state (id) values (?::uuid)
					on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
			if (Boolean.TRUE.equals(this.jdbc.queryForObject("select to_regclass('integration_link_ack') is not null",
					Boolean.class))) {
				this.jdbc.update("delete from integration_link_ack");
			}
		});
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

	/** Fenêtre fixe d'une minute : attendre la suivante s'il reste moins de 30 secondes (horloge de PostgreSQL). */
	private void awaitFreshWindow() {
		double remaining = this.platform.jdbc().sql("select 60 - mod(extract(epoch from clock_timestamp()), 60)::float8")
			.query(Double.class).single();
		if (remaining < 30) {
			try {
				Thread.sleep((long) (remaining * 1000) + 200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private TenantRef provisionCompanyB() throws Exception {
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", SUPER_ADMIN).param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).update();
		String login = this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String superToken = this.json.readTree(login).get("accessToken").asString();
		String code = "it-g17-" + UUID.randomUUID().toString().substring(0, 8);
		String created = this.mvc.perform(post("/api/platform/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + superToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "legalName": "Commerce B SARL", "tradeName": "Commerce B", "ownerLastName": "Kouassi",
					 "adminUsername": "admin"}""".formatted(code)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(this.json.readTree(created).get("id").asString());
		this.createdCompanies.add(id);
		return new TenantRef(id, code);
	}

}
