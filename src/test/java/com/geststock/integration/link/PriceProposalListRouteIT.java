package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lecture des propositions de la liaison par la boutique elle-même (lot 3, G26) :
 * {@code GET /api/integration/v1/prices/proposals}.
 *
 * <ul>
 * <li>Statut par défaut PROPOSED, tous les statuts disponibles.</li>
 * <li>Statut invalide : 400 INVALID_PARAMETER.</li>
 * <li>La boutique ne voit que ses propres propositions (jamais celles d'une autre liaison).</li>
 * <li>Champs exacts de {@code ProposalView}, dont {@code rejectionReason} sur REJECTED.</li>
 * <li>Pagination ({@code page}, {@code size}).</li>
 * <li>Portée manquante : 403 ; sans jeton : 401.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
class PriceProposalListRouteIT {

	private static final String PROPOSALS = "/api/integration/v1/prices/proposals";

	private static final String DECISIONS = "/api/integration-links/{linkId}/price-proposals";

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

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private TenantRef company;

	private UUID admin;

	private final Map<TenantRef, List<UUID[]>> created = new LinkedHashMap<>();

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		String username = "g26.admin." + UUID.randomUUID().toString().substring(0, 6);
		this.admin = TenantContext.callAs(this.company, () -> {
			UUID id = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G26', false) returning id",
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

	// --- Statuts (G26) ---------------------------------------------------------------------------------

	@Test
	void defaultStatusIsProposed() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		propose(shop, product, "4800.00");

		JsonNode page = page(this.mvc.perform(
				get(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(page.get("total").asLong()).isEqualTo(1);
		assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("PROPOSED");
	}

	@Test
	void approvedProposalsAreListedByStatus() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		this.mvc.perform(post(DECISIONS + "/{p}/approve", shop.id(), proposal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isOk());

		// APPROVED : visible avec le bon statut
		JsonNode page = page(this.mvc.perform(
				get(PROPOSALS).param("status", "APPROVED").header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(page.get("total").asLong()).isEqualTo(1);
		assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("APPROVED");

		// PROPOSED : vide
		JsonNode empty = page(this.mvc.perform(
				get(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(empty.get("total").asLong()).isZero();
	}

	@Test
	void rejectedProposalIsVisibleWithRejectionReason() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		String reason = "Prix trop bas pour ce segment " + UUID.randomUUID().toString().substring(0, 6);
		this.mvc.perform(post(DECISIONS + "/{p}/reject", shop.id(), proposal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"" + reason + "\"}"))
			.andExpect(status().isOk());

		JsonNode page = page(this.mvc.perform(
				get(PROPOSALS).param("status", "REJECTED").header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(page.get("total").asLong()).isEqualTo(1);
		JsonNode item = page.get("items").get(0);
		assertThat(item.get("status").asString()).isEqualTo("REJECTED");
		assertThat(item.get("rejectionReason").asString()).isEqualTo(reason);
	}

	@Test
	void supersededProposalsAreListedByStatus() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		propose(shop, product, "4800.00");
		// Deuxième proposition : la première devient SUPERSEDED
		propose(shop, product, "4700.00");

		JsonNode superseded = page(this.mvc.perform(
				get(PROPOSALS).param("status", "SUPERSEDED").header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(superseded.get("total").asLong()).isEqualTo(1);
		assertThat(superseded.get("items").get(0).get("status").asString()).isEqualTo("SUPERSEDED");

		JsonNode proposed = page(this.mvc.perform(
				get(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(proposed.get("total").asLong()).isEqualTo(1);
	}

	@Test
	void withdrawnProposalsAreListedByStatus() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID proposal = propose(shop, product, "4800.00");

		this.mvc.perform(delete(PROPOSALS + "/{p}", proposal)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isOk());

		JsonNode page = page(this.mvc.perform(
				get(PROPOSALS).param("status", "WITHDRAWN").header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(page.get("total").asLong()).isEqualTo(1);
		assertThat(page.get("items").get(0).get("status").asString()).isEqualTo("WITHDRAWN");
	}

	@Test
	void invalidStatusIsRejected() throws Exception {
		Linked shop = active();
		this.mvc.perform(get(PROPOSALS).param("status", "INVALID_STATUS")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	// --- Isolation inter-liaisons (G26) ----------------------------------------------------------------

	@Test
	void shopOnlySeesItsOwnProposals() throws Exception {
		Linked shop1 = active();
		Linked shop2 = active();
		UUID product = product("5000.00");
		propose(shop1, product, "4800.00");
		propose(shop2, product, "4700.00");

		// Shop1 ne voit que sa propre proposition
		JsonNode page1 = page(this.mvc.perform(
				get(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop1.token())));
		assertThat(page1.get("total").asLong()).isEqualTo(1);
		assertThat(page1.get("items").get(0).get("price").asString()).isEqualTo("4800.00");

		// Shop2 ne voit que la sienne
		JsonNode page2 = page(this.mvc.perform(
				get(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop2.token())));
		assertThat(page2.get("total").asLong()).isEqualTo(1);
		assertThat(page2.get("items").get(0).get("price").asString()).isEqualTo("4700.00");
	}

	@Test
	void emptyWhenNoProposals() throws Exception {
		Linked shop = active();
		JsonNode page = page(this.mvc.perform(
				get(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(page.get("total").asLong()).isZero();
		assertThat(page.get("items")).isEmpty();
	}

	// --- Champs de la réponse (G26) --------------------------------------------------------------------

	@Test
	void responseItemContainsAllExpectedFields() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		propose(shop, product, "4800.00");

		JsonNode page = page(this.mvc.perform(
				get(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(page.get("total").asLong()).isEqualTo(1);
		JsonNode item = page.get("items").get(0);

		Set<String> fields = new HashSet<>();
		item.properties().forEach(e -> fields.add(e.getKey()));
		assertThat(fields).containsExactlyInAnyOrder("proposalId", "productId", "productName", "productSku",
				"price", "currency", "status", "referencePriceAtProposal", "currentReferencePrice",
				"note", "proposedAt", "decidedAt", "rejectionReason");
		assertThat(item.get("price").asString()).isEqualTo("4800.00");
		assertThat(item.get("status").asString()).isEqualTo("PROPOSED");
		assertThat(item.get("productId").asString()).isEqualTo(product.toString());
	}

	// --- Pagination (G26) ------------------------------------------------------------------------------

	@Test
	void paginationIsRespected() throws Exception {
		Linked shop = active();
		for (int i = 0; i < 3; i++) {
			propose(shop, product("5000.00"), "480" + i + ".00");
		}

		JsonNode page0 = page(this.mvc.perform(get(PROPOSALS).param("page", "0").param("size", "2")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(page0.get("items")).hasSize(2);
		assertThat(page0.get("total").asLong()).isEqualTo(3);

		JsonNode page1 = page(this.mvc.perform(get(PROPOSALS).param("page", "1").param("size", "2")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));
		assertThat(page1.get("items")).hasSize(1);
	}

	// --- Portée et authentification (G26) --------------------------------------------------------------

	@Test
	void listWithoutProposeScope() throws Exception {
		Linked shop = active(NO_PROPOSE);
		this.mvc.perform(get(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void listWithoutTokenIsUnauthorized() throws Exception {
		this.mvc.perform(get(PROPOSALS)).andExpect(status().isUnauthorized());
	}

	// --- Outils --------------------------------------------------------------------------------------

	private record Linked(UUID id, String token, String currency) {
	}

	private Linked active() {
		return active(PRICING);
	}

	private Linked active(Set<String> scopes) {
		String shopName = "shop-g26-" + UUID.randomUUID().toString().substring(0, 8);
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
					"Cat G26 " + UUID.randomUUID().toString().substring(0, 8));
			UUID prod = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, retail_price) values (?, 'Produit G26', ?, ?::numeric) returning id""",
					UUID.class, "G26-" + UUID.randomUUID().toString().substring(0, 12), category, retailPrice);
			this.created.computeIfAbsent(this.company, ignored -> new ArrayList<>()).add(new UUID[] { prod, category });
			return prod;
		});
	}

	private UUID propose(Linked shop, UUID product, String price) throws Exception {
		String body = "{\"productId\": \"" + product + "\", \"price\": \"" + price + "\", \"currency\": \""
				+ shop.currency() + "\", \"basedOnReferenceVersion\": 0}";
		this.mvc
			.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.header("Idempotency-Key", "g26-cle-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isCreated());
		return TenantContext.callAs(this.company, () -> this.jdbc.queryForObject(
				"select id from channel_price where link_id = ? and product_id = ? order by price_version desc limit 1",
				UUID.class, shop.id(), product));
	}

	private JsonNode page(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8));
	}

	private String userToken(UUID user) {
		return TenantContext.callAs(this.company,
				() -> this.userTokens.issue(this.accounts.findById(user).orElseThrow()).value());
	}

}
