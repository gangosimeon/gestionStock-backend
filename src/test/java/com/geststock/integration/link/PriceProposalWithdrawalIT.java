package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.geststock.platform.PlatformDb;
import com.geststock.security.JwtTokenService;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;

/**
 * Retrait d'une proposition de prix par la boutique (lot 3, G25) :
 * {@code DELETE /api/integration/v1/prices/proposals/{proposalId}}.
 *
 * <ul>
 * <li>Retrait nominal : PROPOSED → WITHDRAWN, 200.</li>
 * <li>Proposition inconnue : 404 PRICE_PROPOSAL_NOT_FOUND.</li>
 * <li>Proposition déjà décidée (APPROVED, SUPERSEDED) : 409 PRICE_PROPOSAL_NOT_PENDING.</li>
 * <li>Liaison suspendue : 403 LINK_SUSPENDED.</li>
 * <li>Concurrence C6 : deux retraits simultanés → un 200, un 409.</li>
 * <li>Portée manquante : 403.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
class PriceProposalWithdrawalIT {

	private static final String PROPOSALS = "/api/integration/v1/prices/proposals";

	private static final String LINK_ACTIONS = "/api/integration-links/{id}";

	private static final Set<String> PRICING = Set.of("catalog:read", "price:propose");

	private static final Set<String> NO_PROPOSE = Set.of("catalog:read");

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

	private TestLinks links;

	private TenantRef company;

	private UUID admin;

	private final Map<TenantRef, List<UUID[]>> created = new LinkedHashMap<>();

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		String username = "g25.admin." + UUID.randomUUID().toString().substring(0, 6);
		this.admin = TenantContext.callAs(this.company, () -> {
			UUID id = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G25', false) returning id",
					UUID.class, username);
			this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = 'ADMIN'",
					id);
			return id;
		});
	}

	@AfterEach
	void tearDown() {
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

	// --- Retrait nominal (G25) -------------------------------------------------------------------------

	@Test
	void withdrawProposedSucceeds() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		this.mvc.perform(delete(PROPOSALS + "/{p}", proposal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("WITHDRAWN"))
			.andExpect(jsonPath("$.proposalId").value(proposal.toString()));

		assertThat(statuses(shop, product)).containsExactly("WITHDRAWN");
	}

	@Test
	void withdrawUnknownProposalIsNotFound() throws Exception {
		Linked shop = active();

		this.mvc.perform(delete(PROPOSALS + "/{p}", UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRICE_PROPOSAL_NOT_FOUND"));
	}

	// --- Proposition déjà décidée : 409 PRICE_PROPOSAL_NOT_PENDING (G25) ----------------------------

	@Test
	void withdrawApprovedProposalIsConflict() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		// Admin approuve la proposition
		this.mvc.perform(post("/api/integration-links/{linkId}/price-proposals/{p}/approve", shop.id(), proposal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isOk());

		// La boutique essaie de retirer : conflit
		this.mvc.perform(delete(PROPOSALS + "/{p}", proposal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PRICE_PROPOSAL_NOT_PENDING"));

		assertThat(statuses(shop, product)).containsExactly("APPROVED");
	}

	@Test
	void withdrawSupersededProposalIsConflict() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID first = propose(shop, product, "4800.00");

		// Deuxième proposition : la première devient SUPERSEDED
		propose(shop, product, "4700.00");

		// La boutique essaie de retirer la proposition remplacée
		this.mvc.perform(delete(PROPOSALS + "/{p}", first)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PRICE_PROPOSAL_NOT_PENDING"));

		assertThat(statuses(shop, product)).containsExactlyInAnyOrder("SUPERSEDED", "PROPOSED");
	}

	// --- Liaison suspendue : 403 LINK_SUSPENDED (G25) ------------------------------------------------

	@Test
	void withdrawOnSuspendedLinkIsForbidden() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		// Admin suspend la liaison
		this.mvc.perform(post(LINK_ACTIONS + "/suspend", shop.id())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isOk());

		// La boutique ne peut plus retirer : liaison suspendue
		this.mvc.perform(delete(PROPOSALS + "/{p}", proposal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("LINK_SUSPENDED"));

		// La proposition est inchangée
		assertThat(statuses(shop, product)).containsExactly("PROPOSED");
	}

	// --- Portée manquante (G25) -----------------------------------------------------------------------

	@Test
	void withdrawWithoutProposeScope() throws Exception {
		Linked shop = active(NO_PROPOSE);

		// Pas besoin d'une vraie proposition : l'autorisation est vérifiée avant la logique métier
		this.mvc.perform(delete(PROPOSALS + "/" + UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void withdrawWithoutTokenIsUnauthorized() throws Exception {
		this.mvc.perform(delete(PROPOSALS + "/" + UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
	}

	// --- Concurrence C6 : deux retraits simultanés → un 200, un 409 (G25) ---------------------------

	@Test
	void concurrentWithdrawalsAreConsistentAcrossMultipleRounds() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 6; round++) {
				Linked shop = active();
				UUID product = product("5000.00");
				UUID proposal = propose(shop, product, "4800.00");
				CyclicBarrier barrier = new CyclicBarrier(2);

				Future<Integer> first = pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.mvc
						.perform(delete(PROPOSALS + "/{p}", proposal)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
						.andReturn()
						.getResponse()
						.getStatus();
				});
				Future<Integer> second = pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.mvc
						.perform(delete(PROPOSALS + "/{p}", proposal)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
						.andReturn()
						.getResponse()
						.getStatus();
				});

				int s1 = first.get(30, TimeUnit.SECONDS);
				int s2 = second.get(30, TimeUnit.SECONDS);

				// L'un des deux doit réussir, l'autre être en conflit
				assertThat(Set.of(s1, s2)).as("retrait concurrent tour %d", round)
					.containsExactlyInAnyOrder(200, 409);
				assertThat(statuses(shop, product)).as("WITHDRAWN après retrait concurrent, tour %d", round)
					.containsExactly("WITHDRAWN");
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// --- Outils --------------------------------------------------------------------------------------

	private record Linked(UUID id, String token, String currency) {
	}

	private Linked active() {
		return active(PRICING);
	}

	private Linked active(Set<String> scopes) {
		String shopName = "shop-g25-" + UUID.randomUUID().toString().substring(0, 8);
		Link link = this.links.active(this.links.client(), this.company.companyId(), shopName, scopes);
		String clientId = this.platform.jdbc()
			.sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId())
			.query(String.class)
			.single();
		String token = this.tokens.issue(link.id(), clientId, scopes).value();
		return new Linked(link.id(), token, link.currency());
	}

	private UUID product(String retailPrice) {
		return TenantContext.callAs(this.company, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values (?) returning id", UUID.class,
					"Cat G25 " + UUID.randomUUID().toString().substring(0, 8));
			UUID prod = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, retail_price) values (?, 'Produit G25', ?, ?::numeric) returning id""",
					UUID.class, "G25-" + UUID.randomUUID().toString().substring(0, 12), category, retailPrice);
			this.created.computeIfAbsent(this.company, ignored -> new ArrayList<>()).add(new UUID[] { prod, category });
			return prod;
		});
	}

	private UUID propose(Linked shop, UUID product, String price) throws Exception {
		String body = "{\"productId\": \"" + product + "\", \"price\": \"" + price + "\", \"currency\": \""
				+ shop.currency() + "\", \"basedOnReferenceVersion\": 0}";
		this.mvc
			.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.header("Idempotency-Key", "g25-cle-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated());
		return TenantContext.callAs(this.company, () -> this.jdbc.queryForObject(
				"select id from channel_price where link_id = ? and product_id = ? order by price_version desc limit 1",
				UUID.class, shop.id(), product));
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

}
