package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.geststock.platform.PlatformDb;
import com.geststock.security.JwtTokenService;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Décision sur une liaison révoquée (lot 3, étape G24) : {@code 409 LINK_PROPOSAL_REVOKED} pour approbation et refus ;
 * liaison suspendue acceptée ; extension G15 à {@link PriceProposalController} : caractères de contrôle dans le motif
 * de refus et corps illisible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
class PriceProposalRevocationIT {

	private static final String PROPOSALS = "/api/integration/v1/prices/proposals";

	private static final String DECISIONS = "/api/integration-links/{linkId}/price-proposals";

	private static final String LINK_ACTIONS = "/api/integration-links/{id}";

	private static final Set<String> PRICING = Set.of("catalog:read", "price:propose");

	/** Loggers limités à INFO par application.yml : laissés à ce niveau pendant la capture TRACE. */
	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	private static final Set<String> RESPONSE_WRITERS = Set.of(
			"org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
			"org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

	@Autowired
	private JwtTokenService userTokens;

	@Autowired
	private UserAccountService accounts;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private TestLinks links;

	private TenantRef company;

	private UUID admin;

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

	private final Map<TenantRef, List<UUID[]>> created = new LinkedHashMap<>();

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		String username = "g24rev.admin." + UUID.randomUUID().toString().substring(0, 6);
		this.admin = TenantContext.callAs(this.company, () -> {
			UUID id = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G24Rev', false) returning id",
					UUID.class, username);
			this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = 'ADMIN'", id);
			return id;
		});
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
		((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
			.detachAppender(this.logs);
		this.levels.forEach(Logger::setLevel);
		this.levels.clear();
		try {
			List<String> messages = this.logs.list.stream()
				.filter(event -> !RESPONSE_WRITERS.contains(event.getLoggerName()))
				.map(PriceProposalRevocationIT::render).toList();
			assertThat(messages).as("aucune valeur reçue ni motif dans les journaux")
				.allSatisfy(message -> List.copyOf(this.sensitive)
					.forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			this.created.forEach((company, rows) -> TenantContext.runAs(company, () -> {
				this.jdbc.update("delete from integration_idempotency");
				this.jdbc.update("delete from channel_price_head");
				this.jdbc.update("delete from channel_price");
				for (UUID[] row : rows) {
					this.jdbc.update("delete from product where id = ?", row[0]);
					this.jdbc.update("delete from category where id = ?", row[1]);
				}
			}));
			this.created.clear();
			TenantContext.runAs(this.company, () -> {
				this.jdbc.update("delete from user_role where user_id = ?", this.admin);
				this.jdbc.update("delete from refresh_token where user_id = ?", this.admin);
				this.jdbc.update("delete from app_user where id = ?", this.admin);
			});
			this.links.cleanUp();
		}
	}

	// --- Liaison révoquée : décisions bloquées (G24) -------------------------------------------------------

	@Test
	void approvalOnRevokedLinkIsConflict() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		// Révoquer la liaison
		decide(post(LINK_ACTIONS + "/revoke", shop.id()), null).andExpect(status().isOk());

		// Approbation impossible : liaison révoquée
		decide(post(DECISIONS + "/{p}/approve", shop.id(), proposal), null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LINK_PROPOSAL_REVOKED"));

		// La proposition est inchangée
		assertThat(statuses(shop, product)).containsExactly("PROPOSED");
	}

	@Test
	void rejectionOnRevokedLinkIsConflict() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		decide(post(LINK_ACTIONS + "/revoke", shop.id()), null).andExpect(status().isOk());

		String reason = remember("Liaison révoquée " + UUID.randomUUID().toString().substring(0, 8));
		decide(post(DECISIONS + "/{p}/reject", shop.id(), proposal),
				"{\"reason\": \"" + reason + "\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LINK_PROPOSAL_REVOKED"));

		assertThat(statuses(shop, product)).containsExactly("PROPOSED");
	}

	// --- Liaison suspendue : décisions acceptées ------------------------------------------------------------

	@Test
	void approvalOnSuspendedLinkSucceeds() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		decide(post(LINK_ACTIONS + "/suspend", shop.id()), null).andExpect(status().isOk());

		decide(post(DECISIONS + "/{p}/approve", shop.id(), proposal), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));
	}

	@Test
	void rejectionOnSuspendedLinkSucceeds() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		decide(post(LINK_ACTIONS + "/suspend", shop.id()), null).andExpect(status().isOk());

		String reason = remember("Marge insuffisante " + UUID.randomUUID().toString().substring(0, 8));
		decide(post(DECISIONS + "/{p}/reject", shop.id(), proposal),
				"{\"reason\": \"" + reason + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
	}

	// --- G15 : caractères de contrôle dans le motif de refus (G24 : @FreeText + IntegrationBodyAdvice) --------

	@Test
	void controlCharacterInRejectionReasonIsRejectedWithoutLogging() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		// Caractères de contrôle interdits même dans un champ @FreeText : envoyés sous forme d'échappement JSON
		// (backslash + uXXXX) pour que le corps soit du JSON valide parsé par Jackson avant IntegrationBodyAdvice.
		for (int cp : new int[] { 0x00, 0x01, 0x0B, 0x1B, 0x1F, 0x7F }) {
			String marker = remember("g24ctrl-" + UUID.randomUUID().toString().substring(0, 8));
			String ctrlEscaped = String.format("\\u%04x", cp);
			decide(post(DECISIONS + "/{p}/reject", shop.id(), proposal),
					"{\"reason\": \"" + marker + ctrlEscaped + "\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}

		// La proposition est inchangée
		assertThat(statuses(shop, product)).containsExactly("PROPOSED");
	}

	@Test
	void tabAndNewlineAreAllowedInRejectionReason() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		// Tab et saut de ligne permis dans un champ @FreeText : envoyés comme échappements JSON (\t, \n).
		String reason = remember("Raison avec \\t tabulation \\n et saut");
		decide(post(DECISIONS + "/{p}/reject", shop.id(), proposal), "{\"reason\": \"" + reason + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
	}

	@Test
	void malformedJsonOnRejectRouteIsHandledWithoutLogging() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		String marker = remember("g24malform-" + UUID.randomUUID().toString().substring(0, 8));
		decide(post(DECISIONS + "/{p}/reject", shop.id(), proposal),
				"{\"reason\": \"" + marker + "\" invalid json}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

		assertThat(statuses(shop, product)).containsExactly("PROPOSED");
	}

	// --- G15 : valeurs longues et status invalide -----------------------------------------------------------

	@Test
	void reasonTooLongIsRejectedWithoutLogging() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		// Motif de 501 caractères (> 500) : validation @Size échoue, valeur masquée dans les journaux
		String longReason = remember("R".repeat(501));
		decide(post(DECISIONS + "/{p}/reject", shop.id(), proposal), "{\"reason\": \"" + longReason + "\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		// La proposition est inchangée
		assertThat(statuses(shop, product)).containsExactly("PROPOSED");
		// tearDown vérifie que longReason n'est pas dans les journaux
	}

	@Test
	void invalidStatusOnCrossLinkListIsRejectedWithoutLogging() throws Exception {
		// Valeur de status invalide sur GET /api/price-proposals : 400 INVALID_PARAMETER, valeur non journalisée
		String badStatus = remember("g24-bad-status-" + UUID.randomUUID().toString().substring(0, 8));
		this.mvc
			.perform(get("/api/price-proposals").param("status", badStatus)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		// tearDown vérifie que badStatus n'est pas dans les journaux
	}

	// --- Concurrence (G24) : révocation pendant une approbation -----------------------------------------------

	@Test
	void revocationConcurrentWithApprovalIsConsistentAcrossMultipleRounds() throws Exception {
		// Révocation et approbation concurrentes : résultat cohérent (200 ou 409), jamais incohérent.
		// Le garde re-lit le statut de la liaison sous le verrou de tête (ChannelPrices.decide).
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 6; round++) {
				Linked shop = active();
				UUID product = product("5000.00");
				UUID proposal = propose(shop, product, "4800.00");
				// Jetons générés sur le thread principal (TenantContext disponible)
				String adminToken = userToken(this.admin);
				CyclicBarrier barrier = new CyclicBarrier(2);

				Future<Integer> approveFuture = pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.mvc
						.perform(post(DECISIONS + "/{p}/approve", shop.id(), proposal)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
						.andReturn()
						.getResponse()
						.getStatus();
				});
				Future<Integer> revokeFuture = pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.mvc
						.perform(post(LINK_ACTIONS + "/revoke", shop.id())
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
						.andReturn()
						.getResponse()
						.getStatus();
				});

				int approveStatus = approveFuture.get(30, TimeUnit.SECONDS);
				int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);

				// La révocation doit toujours réussir
				assertThat(revokeStatus).as("révocation tour %d", round).isEqualTo(200);
				// L'approbation : 200 (avant révocation) ou 409 (révocation interceptée), jamais autre chose
				assertThat(approveStatus).as("approbation tour %d", round).isIn(200, 409);

				if (approveStatus == 409) {
					// Révocation avant approbation : proposition inchangée
					assertThat(statuses(shop, product)).as("PROPOSED inchangé, tour %d", round)
						.containsExactly("PROPOSED");
				}
				// Si 200 : approbation avant révocation, acceptable
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void controlCharacterInPathVariablesIsRejectedForPriceProposalRoutes() throws Exception {
		// UUID avec caractère de contrôle encodé dans le chemin : refusé par ControlCharacterPathCheck
		String badLinkId = UUID.randomUUID().toString().replace("-", "-\u0001");
		this.mvc.perform(get("/api/integration-links/" + badLinkId + "/price-proposals")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isBadRequest());
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private record Linked(UUID id, String token, String currency) {
	}

	private Linked active() {
		String shopName = "shop-g24rev-" + UUID.randomUUID().toString().substring(0, 8);
		Link link = this.links.active(this.links.client(), this.company.companyId(), shopName, PRICING);
		String clientId = this.platform.jdbc()
			.sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		String token = this.tokens.issue(link.id(), clientId, PRICING).value();
		return new Linked(link.id(), token, link.currency());
	}

	private UUID product(String retailPrice) {
		return TenantContext.callAs(this.company, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values (?) returning id", UUID.class,
					"Cat G24Rev " + UUID.randomUUID().toString().substring(0, 8));
			UUID product = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, retail_price) values (?, 'Produit G24Rev', ?, ?::numeric) returning id""",
					UUID.class, "G24R-" + UUID.randomUUID().toString().substring(0, 12), category, retailPrice);
			this.created.computeIfAbsent(this.company, ignored -> new ArrayList<>()).add(new UUID[] { product, category });
			return product;
		});
	}

	private UUID propose(Linked shop, UUID product, String price) throws Exception {
		String body = "{\"productId\": \"" + product + "\", \"price\": \"" + price + "\", \"currency\": \""
				+ shop.currency() + "\", \"basedOnReferenceVersion\": 0}";
		this.mvc.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
			.header("Idempotency-Key", "g24rev-cle-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated());
		return TenantContext.callAs(this.company, () -> this.jdbc.queryForObject(
				"select id from channel_price where link_id = ? and product_id = ? order by price_version desc limit 1",
				UUID.class, shop.id(), product));
	}

	private ResultActions decide(MockHttpServletRequestBuilder request, String body) throws Exception {
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return this.mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)));
	}

	private List<String> statuses(Linked shop, UUID product) {
		return TenantContext.callAs(this.company, () -> this.jdbc.queryForList(
				"select status from channel_price where link_id = ? and product_id = ? order by price_version",
				String.class, shop.id(), product));
	}

	private String userToken(UUID user) {
		return TenantContext.callAs(this.company,
				() -> this.userTokens.issue(this.accounts.findById(user).orElseThrow()).value());
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
