package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.integration.ChangeEvent;
import com.geststock.integration.ChangeFeedService;
import com.geststock.integration.ChangeFilter;
import com.geststock.integration.IntegrationEventType;
import com.geststock.integration.IntegrationEvents;
import com.geststock.integration.LinkRef;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.provisioning.ProvisionerAccess;
import com.geststock.security.JwtTokenService;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Approbation des prix proposés par les boutiques (lot 3, étape G22b ; D2, C6, v16-D3 ; décisions P2, P9, P10), avec
 * deux entreprises réelles : A (l'entreprise de test historique, deux liaisons) et B (provisionnée pour ce test).
 *
 * <ul>
 * <li>Décisions des utilisateurs GestStock ({@code integration:price-approve}) : approbation, refus motivé ; une
 * décision sur une proposition qui n'est plus en attente est un conflit, sans rien changer ; sous concurrence avec une
 * nouvelle proposition (C6), aucun prix n'est approuvé à tort.</li>
 * <li>Prix approuvé servi à la boutique par {@code GET /prices} ; un changement du prix de référence ne le modifie
 * jamais et lève seulement {@code referenceChangedSinceApproval} (v16-D3).</li>
 * <li>Événement {@code CHANNEL_PRICE} mince (P2) : recalculé pour la liaison qui lit le flux, jamais avec les prix
 * d'une autre liaison.</li>
 * <li>Liaison d'une autre entreprise ou inconnue : même {@code 404} ; proposition d'une autre liaison : {@code 404} ;
 * sans la permission : {@code 403} ; trace d'audit de chaque décision.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PriceApprovalRouteIT {

	private static final String PROPOSALS = "/api/integration/v1/prices/proposals";

	private static final String PRICES = "/api/integration/v1/prices";

	private static final String DECISIONS = "/api/integration-links/{linkId}/price-proposals";

	private static final String SUPER_ADMIN = "it_g22b_superadmin";

	private static final String SUPER_ADMIN_PASSWORD = "Plateforme-Test-2026";

	private static final Set<String> PRICING = Set.of("catalog:read", "price:propose");

	private static final Set<String> CAPPED_LOGGERS = Set.of("org.postgresql", "org.springframework.web.method.HandlerMethod",
			"org.springframework.web.servlet.DispatcherServlet");

	/**
	 * Écriture du corps de réponse par Spring MVC (DEBUG, TRACE), pour une réponse {@code ResponseEntity} ou un corps
	 * renvoyé directement : limite assumée (v17-D2), comme G22a. La réponse renvoie à la boutique ses propres prix et le
	 * motif d'un refus qui lui est destiné ; ces niveaux ne sont jamais activés en production.
	 */
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
	private ChangeFeedService feed;

	@Autowired
	private IntegrationEvents events;

	@Autowired
	private TransactionTemplate transactions;

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

	private TestLinks links;

	private TenantRef companyA;

	private TenantRef companyB;

	private UUID admin;

	private UUID cashier;

	private Boolean publicationBefore;

	private final List<UUID> createdCompanies = new ArrayList<>();

	private final List<String> sensitive = java.util.Collections.synchronizedList(new ArrayList<>());

	private final Map<TenantRef, List<UUID[]>> created = new LinkedHashMap<>();

	private final Map<Logger, Level> levels = new LinkedHashMap<>();

	private ListAppender<ILoggingEvent> logs;

	@BeforeAll
	void provision() throws Exception {
		this.links = new TestLinks(this.platform, this.registry);
		this.companyA = new TenantRef(this.links.legacyCompany(), "test");
		this.companyB = provisionCompanyB();
		this.admin = user("ADMIN");
		this.cashier = user("CAISSIER");
	}

	@BeforeEach
	void setUp() {
		this.publicationBefore = TenantContext.callAs(this.companyA,
				() -> this.jdbc.queryForObject("select enabled from integration_publication", Boolean.class));
		TenantContext.runAs(this.companyA, () -> this.jdbc.update("update integration_publication set enabled = true"));
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
			List<String> messages = this.logs.list.stream().filter(event -> !RESPONSE_WRITERS.contains(event.getLoggerName()))
				.map(PriceApprovalRouteIT::render).toList();
			assertThat(messages).as("aucune valeur reçue ni jeton dans les journaux")
				.allSatisfy(message -> List.copyOf(this.sensitive).forEach(value -> assertThat(message).doesNotContain(value)));
		}
		finally {
			this.sensitive.clear();
			TenantContext.runAs(this.companyA, () -> this.jdbc.update("update integration_publication set enabled = ?",
					this.publicationBefore));
			this.created.forEach((company, rows) -> TenantContext.runAs(company, () -> {
				this.jdbc.update("delete from integration_idempotency");
				this.jdbc.update("delete from channel_price_head");
				this.jdbc.update("delete from channel_price");
				for (UUID[] row : rows) {
					this.jdbc.update("delete from integration_outbox where object_id = ?", row[0]);
					this.jdbc.update("delete from product where id = ?", row[0]);
					this.jdbc.update("delete from category where id = ?", row[1]);
				}
			}));
			this.created.clear();
			this.links.cleanUp();
		}
	}

	@AfterAll
	void drop() throws Exception {
		TenantContext.runAs(this.companyA, () -> {
			this.jdbc.update("delete from user_role where user_id in (?, ?)", this.admin, this.cashier);
			this.jdbc.update("delete from refresh_token where user_id in (?, ?)", this.admin, this.cashier);
			this.jdbc.update("delete from app_user where id in (?, ?)", this.admin, this.cashier);
		});
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

	// --- Approbation et refus ---------------------------------------------------------------------------------

	@Test
	void anApprovedProposalBecomesTheShopsApprovedPriceAndIsAudited() throws Exception {
		Linked shop = active(PRICING);
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "5500.00", 0, "Prix de lancement");

		JsonNode pending = page(decisions(get(DECISIONS, shop.id()), this.admin));
		assertThat(pending.get("items")).singleElement().satisfies(item -> {
			assertThat(item.get("proposalId").asString()).isEqualTo(proposal.toString());
			assertThat(item.get("price").asString()).isEqualTo("5500.00");
			assertThat(item.get("referencePriceAtProposal").asString()).isEqualTo("5000.00");
			assertThat(item.get("currentReferencePrice").asString()).isEqualTo("5000.00");
			assertThat(item.get("status").asString()).isEqualTo("PROPOSED");
			assertThat(item.get("productName").asString()).isEqualTo("Produit G22b");
		});

		decide(shop, proposal, "approve", null).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));

		JsonNode price = item(prices(shop, product.toString()), product);
		assertThat(price.get("approved").get("price").asString()).isEqualTo("5500.00");
		assertThat(price.get("pending").isNull()).as("la proposition approuvée n'est plus en attente").isTrue();
		assertThat(price.get("referenceChangedSinceApproval").asBoolean()).isFalse();
		assertThat(audits("PRICE_PROPOSAL_APPROVED", proposal)).isEqualTo(1);
		assertThat(page(decisions(get(DECISIONS, shop.id()), this.admin)).get("items")).as("plus rien en attente").isEmpty();
	}

	@Test
	void aRejectionRequiresAReasonAndIsVisibleToTheShop() throws Exception {
		Linked shop = active(PRICING);
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4000.00", 0, null);

		decide(shop, proposal, "reject", "{}").andExpect(status().isBadRequest());
		decide(shop, proposal, "reject", "{\"reason\": \"   \"}").andExpect(status().isBadRequest());
		decide(shop, proposal, "reject", "{\"reason\": \"" + "x".repeat(501) + "\"}").andExpect(status().isBadRequest());
		assertThat(statuses(shop, product)).containsExactly("PROPOSED");

		String reason = remember("Marge insuffisante " + UUID.randomUUID().toString().substring(0, 8));
		decide(shop, proposal, "reject", "{\"reason\": \"" + reason + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));

		JsonNode price = item(prices(shop, product.toString()), product);
		assertThat(price.get("approved").isNull()).isTrue();
		assertThat(price.get("pending").get("status").asString()).isEqualTo("REJECTED");
		assertThat(price.get("pending").get("rejectionReason").asString()).isEqualTo(reason);
		assertThat(audits("PRICE_PROPOSAL_REJECTED", proposal)).isEqualTo(1);
		assertThat(page(decisions(get(DECISIONS, shop.id()).param("status", "REJECTED"), this.admin)).get("items"))
			.singleElement().satisfies(item -> assertThat(item.get("rejectionReason").asString()).isEqualTo(reason));
	}

	@Test
	void aDecisionOnASupersededOrAlreadyDecidedProposalIsAConflictAndChangesNothing() throws Exception {
		Linked shop = active(PRICING);
		UUID product = product("5000.00");
		UUID first = propose(shop, product, "5100.00", 0, null);
		UUID second = propose(shop, product, "5200.00", 0, null);

		decide(shop, first, "approve", null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PRICE_PROPOSAL_NOT_PENDING"));
		assertThat(statuses(shop, product)).containsExactly("SUPERSEDED", "PROPOSED");

		decide(shop, second, "approve", null).andExpect(status().isOk());
		decide(shop, second, "approve", null).andExpect(status().isConflict());
		decide(shop, second, "reject", "{\"reason\": \"Trop tard\"}").andExpect(status().isConflict());
		assertThat(statuses(shop, product)).containsExactly("SUPERSEDED", "APPROVED");
		assertThat(item(prices(shop, product.toString()), product).get("approved").get("price").asString()).isEqualTo("5200.00");
	}

	@Test
	void anApprovalRacingANewProposalNeverApprovesTheWrongPrice() throws Exception {
		Linked shop = active(PRICING);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 6; round++) {
				UUID product = product("5000.00");
				UUID old = propose(shop, product, "5100.00", 0, null);
				CyclicBarrier start = new CyclicBarrier(2);
				Future<Integer> approval = pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return decide(shop, old, "approve", null).andReturn().getResponse().getStatus();
				});
				Future<Integer> newer = pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return proposeRaw(shop, product, "5300.00", 0).andReturn().getResponse().getStatus();
				});
				int approved = approval.get(30, TimeUnit.SECONDS);
				assertThat(newer.get(30, TimeUnit.SECONDS)).isEqualTo(201);
				JsonNode price = item(prices(shop, product.toString()), product);
				if (approved == 200) {
					assertThat(statuses(shop, product)).as("approuvée avant la nouvelle").containsExactly("APPROVED", "PROPOSED");
					assertThat(price.get("approved").get("price").asString()).isEqualTo("5100.00");
				}
				else {
					assertThat(approved).as("remplacée avant l'approbation").isEqualTo(409);
					assertThat(statuses(shop, product)).containsExactly("SUPERSEDED", "PROPOSED");
					assertThat(price.get("approved").isNull()).as("aucun prix approuvé à tort").isTrue();
				}
				assertThat(price.get("pending").get("price").asString()).isEqualTo("5300.00");
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// --- Accès ------------------------------------------------------------------------------------------------

	@Test
	void decisionsRequireThePriceApprovalPermission() throws Exception {
		Linked shop = active(PRICING);
		UUID proposal = propose(shop, product("5000.00"), "5500.00", 0, null);
		decisions(get(DECISIONS, shop.id()), this.cashier).andExpect(status().isForbidden());
		decide(shop, proposal, "approve", null, this.cashier).andExpect(status().isForbidden());
		decide(shop, proposal, "reject", "{\"reason\": \"Non\"}", this.cashier).andExpect(status().isForbidden());
		assertThat(statuses(shop, proposalProduct(proposal))).containsExactly("PROPOSED");
	}

	@Test
	void aLinkOfAnotherCompanyOrUnknownIsNotFoundAndAProposalOfAnotherLinkToo() throws Exception {
		Link foreign = this.links.active(this.links.client(), this.companyB.companyId(), "shop-g22b-b-" + UUID.randomUUID(),
				PRICING);
		Linked shop = active(PRICING);
		Linked other = active(PRICING);
		UUID proposal = propose(shop, product("5000.00"), "5500.00", 0, null);

		for (UUID link : List.of(foreign.id(), UUID.randomUUID())) {
			decisions(get(DECISIONS, link), this.admin).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
			decisions(post(DECISIONS + "/{p}/approve", link, proposal), this.admin).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
		}
		decisions(post(DECISIONS + "/{p}/approve", other.id(), proposal), this.admin).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRICE_PROPOSAL_NOT_FOUND"));
		decisions(post(DECISIONS + "/{p}/approve", shop.id(), UUID.randomUUID()), this.admin).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRICE_PROPOSAL_NOT_FOUND"));
		assertThat(statuses(shop, proposalProduct(proposal))).containsExactly("PROPOSED");
	}

	@Test
	void theStatusFilterIsCheckedAndThePageIsBounded() throws Exception {
		Linked shop = active(PRICING);
		decisions(get(DECISIONS, shop.id()).param("status", "UNKNOWN"), this.admin).andExpect(status().isBadRequest());
		decisions(get(DECISIONS, shop.id()).param("size", "201"), this.admin).andExpect(status().isBadRequest());
		propose(shop, product("5000.00"), "5500.00", 0, null);
		propose(shop, product("6000.00"), "6500.00", 0, null);
		JsonNode first = page(decisions(get(DECISIONS, shop.id()).param("page", "0").param("size", "1"), this.admin));
		assertThat(first.get("items")).hasSize(1);
		assertThat(first.get("total").asLong()).isEqualTo(2);
	}

	// --- Prix de référence (v16-D3) -----------------------------------------------------------------------------

	@Test
	void aReferencePriceChangeNeverChangesTheApprovedPriceAndOnlyRaisesTheFlag() throws Exception {
		Linked shop = active(PRICING);
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "5500.00", 0, null);
		decide(shop, proposal, "approve", null).andExpect(status().isOk());

		TenantContext.runAs(this.companyA, () -> this.jdbc.update(
				"update product set retail_price = 5200.00, version = version + 1 where id = ?", product));

		JsonNode price = item(prices(shop, product.toString()), product);
		assertThat(price.get("approved").get("price").asString()).as("prix approuvé inchangé").isEqualTo("5500.00");
		assertThat(price.get("referencePrice").asString()).isEqualTo("5200.00");
		assertThat(price.get("referenceChangedSinceApproval").asBoolean()).isTrue();

		TenantContext.runAs(this.companyA, () -> this.jdbc.update(
				"update product set name = 'Produit G22b renommé', version = version + 1 where id = ?", product));
		assertThat(item(prices(shop, product.toString()), product).get("referenceChangedSinceApproval").asBoolean())
			.as("toujours vrai : le prix, pas la version, compte").isTrue();
		TenantContext.runAs(this.companyA, () -> this.jdbc.update(
				"update product set retail_price = 5000.00, version = version + 1 where id = ?", product));
		assertThat(item(prices(shop, product.toString()), product).get("referenceChangedSinceApproval").asBoolean())
			.as("prix de référence revenu à celui de l'approbation").isFalse();
	}

	// --- Événement CHANNEL_PRICE mince (P2) ----------------------------------------------------------------------

	@Test
	void theChannelPriceEventIsComputedForTheReadingLinkOnly() throws Exception {
		Linked shop = active(PRICING);
		Linked other = active(PRICING);
		UUID product = product("5000.00");
		long after = lastSeq();
		UUID proposal = propose(shop, product, "5700.00", 0, null);
		// Disponibilité du même produit dans la même page : deux événements minces distincts, aucun ne masque l'autre.
		TenantContext.runAs(this.companyA, () -> this.transactions
			.executeWithoutResult(status -> this.events.publishAvailability(List.of(product))));
		decide(shop, proposal, "approve", null).andExpect(status().isOk());

		ChangeEvent forShop = channelPrice(shop, after, product);
		assertThat(forShop.version()).as("événement mince : version = seq").isEqualTo(forShop.seq());
		assertThat(forShop.data().get("approved").get("price").asString()).isEqualTo("5700.00");
		assertThat(events(shop, after, product)).as("disponibilité servie aussi")
			.contains(IntegrationEventType.AVAILABILITY, IntegrationEventType.CHANNEL_PRICE);

		ChangeEvent forOther = channelPrice(other, after, product);
		assertThat(forOther.data().get("approved").isNull()).as("jamais le prix approuvé d'une autre liaison").isTrue();
		assertThat(forOther.data().get("pending").isNull()).isTrue();
		assertThat(forOther.data().toString()).doesNotContain("5700").doesNotContain(proposal.toString());

		UUID rejected = propose(shop, product, "5900.00", 0, null);
		long beforeRejection = lastSeq();
		decide(shop, rejected, "reject", "{\"reason\": \"Non merci\"}").andExpect(status().isOk());
		assertThat(channelPrice(shop, beforeRejection, product).data().get("pending").get("status").asString())
			.as("un refus publie aussi").isEqualTo("REJECTED");
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private record Linked(UUID id, String token, String currency) {
	}

	private Linked active(Set<String> scopes) {
		Link link = this.links.active(this.links.client(), this.companyA.companyId(), "shop-g22b-" + UUID.randomUUID(), scopes);
		String clientId = this.platform.jdbc().sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		return new Linked(link.id(), remember(this.tokens.issue(link.id(), clientId, scopes).value()), link.currency());
	}

	private UUID product(String retailPrice) {
		return TenantContext.callAs(this.companyA, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values (?) returning id", UUID.class,
					"Cat G22b " + UUID.randomUUID().toString().substring(0, 8));
			UUID product = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, retail_price) values (?, 'Produit G22b', ?, ?::numeric)
					returning id""", UUID.class, "G22B-" + UUID.randomUUID().toString().substring(0, 12), category, retailPrice);
			this.created.computeIfAbsent(this.companyA, ignored -> new ArrayList<>()).add(new UUID[] { product, category });
			return product;
		});
	}

	/** Proposition créée par la boutique (route G22a) ; son identifiant. */
	private UUID propose(Linked shop, UUID product, String price, long basedOn, String note) throws Exception {
		if (note != null) {
			remember(note);
		}
		String body = "{\"productId\": \"" + product + "\", \"price\": \"" + price + "\", \"currency\": \"" + shop.currency() + "\""
				+ ", \"basedOnReferenceVersion\": " + basedOn + (note != null ? ", \"note\": \"" + note + "\"" : "") + "}";
		this.mvc.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
			.header("Idempotency-Key", remember("g22b-cle-" + UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated());
		return TenantContext.callAs(this.companyA, () -> this.jdbc.queryForObject("""
				select id from channel_price where link_id = ? and product_id = ? order by price_version desc limit 1""",
				UUID.class, shop.id(), product));
	}

	private ResultActions proposeRaw(Linked shop, UUID product, String price, long basedOn) throws Exception {
		String body = "{\"productId\": \"" + product + "\", \"price\": \"" + price + "\", \"currency\": \"" + shop.currency() + "\""
				+ ", \"basedOnReferenceVersion\": " + basedOn + "}";
		return this.mvc.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
			.header("Idempotency-Key", remember("g22b-cle-" + UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions decide(Linked shop, UUID proposal, String action, String body) throws Exception {
		return decide(shop, proposal, action, body, this.admin);
	}

	private ResultActions decide(Linked shop, UUID proposal, String action, String body, UUID user) throws Exception {
		MockHttpServletRequestBuilder request = post(DECISIONS + "/{p}/" + action, shop.id(), proposal);
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return decisions(request, user);
	}

	private ResultActions decisions(MockHttpServletRequestBuilder request, UUID user) throws Exception {
		return this.mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(user)));
	}

	private JsonNode page(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8));
	}

	private JsonNode prices(Linked shop, String productIds) throws Exception {
		return this.json.readTree(this.mvc.perform(get(PRICES).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
			.param("productIds", productIds)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8));
	}

	private static JsonNode item(JsonNode page, UUID product) {
		for (JsonNode item : page.get("items")) {
			if (item.get("productId").asString().equals(product.toString())) {
				return item;
			}
		}
		throw new AssertionError("produit absent : " + product);
	}

	private List<String> statuses(Linked shop, UUID product) {
		return TenantContext.callAs(this.companyA, () -> this.jdbc.queryForList(
				"select status from channel_price where link_id = ? and product_id = ? order by price_version", String.class,
				shop.id(), product));
	}

	private UUID proposalProduct(UUID proposal) {
		return TenantContext.callAs(this.companyA,
				() -> this.jdbc.queryForObject("select product_id from channel_price where id = ?", UUID.class, proposal));
	}

	private int audits(String action, UUID proposal) {
		return TenantContext.callAs(this.companyA, () -> this.jdbc.queryForObject(
				"select count(*) from audit_log where action = ? and entity_type = 'CHANNEL_PRICE' and entity_id = ?",
				Integer.class, action, proposal.toString()));
	}

	/** Dernier seq attribué, après séquencement des événements déjà validés. */
	private long lastSeq() {
		return TenantContext.callAs(this.companyA, () -> {
			long before = this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class);
			this.feed.read(new LinkRef(UUID.randomUUID()), before, 1, ChangeFilter.ALL);
			return this.jdbc.queryForObject("select last_seq from integration_feed_state", Long.class);
		});
	}

	private List<ChangeEvent> read(Linked shop, long after) {
		return TenantContext.callAs(this.companyA,
				() -> this.feed.read(new LinkRef(shop.id()), after, 500, ChangeFilter.ALL).items());
	}

	private ChangeEvent channelPrice(Linked shop, long after, UUID product) {
		return read(shop, after).stream()
			.filter(event -> event.type() == IntegrationEventType.CHANNEL_PRICE && event.objectId().equals(product))
			.reduce((first, second) -> second)
			.orElseThrow(() -> new AssertionError("aucun événement CHANNEL_PRICE pour " + product));
	}

	private List<IntegrationEventType> events(Linked shop, long after, UUID product) {
		return read(shop, after).stream().filter(event -> event.objectId().equals(product)).map(ChangeEvent::type).toList();
	}

	private UUID user(String role) {
		String username = "g22b." + role.toLowerCase() + "." + UUID.randomUUID().toString().substring(0, 6);
		return TenantContext.callAs(this.companyA, () -> {
			UUID id = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G22b', false) returning id",
					UUID.class, username);
			this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = ?", id, role);
			return id;
		});
	}

	private String userToken(UUID user) {
		return remember(TenantContext.callAs(this.companyA,
				() -> this.userTokens.issue(this.accounts.findById(user).orElseThrow()).value()));
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

	private TenantRef provisionCompanyB() throws Exception {
		this.platform.jdbc().sql("delete from platform.platform_admin where username = :u").param("u", SUPER_ADMIN).update();
		this.platform.jdbc()
			.sql("insert into platform.platform_admin (username, full_name, password_hash, must_change_password) values (:u, 'IT', :h, false)")
			.param("u", SUPER_ADMIN).param("h", this.passwordEncoder.encode(SUPER_ADMIN_PASSWORD)).update();
		String login = this.mvc.perform(post("/api/platform/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\": \"" + SUPER_ADMIN + "\", \"password\": \"" + SUPER_ADMIN_PASSWORD + "\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String superToken = this.json.readTree(login).get("accessToken").asString();
		String code = "it-g22b-" + UUID.randomUUID().toString().substring(0, 8);
		String body = this.mvc.perform(post("/api/platform/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + superToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"code": "%s", "legalName": "Commerce B SARL", "tradeName": "Commerce B", "ownerLastName": "Kouassi",
					 "adminUsername": "admin"}""".formatted(code)))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE"))
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(this.json.readTree(body).get("id").asString());
		this.createdCompanies.add(id);
		return new TenantRef(id, code);
	}

}
