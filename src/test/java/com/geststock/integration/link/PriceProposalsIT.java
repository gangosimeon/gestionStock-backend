package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

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

import com.geststock.common.security.Permissions;
import com.geststock.platform.PlatformDb;
import com.geststock.security.JwtTokenService;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccountService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/price-proposals} : liste croisée des propositions en attente, toutes liaisons non révoquées confondues
 * (lot 3, étape G24). Vérifie l'exclusion des liaisons révoquées, la présence des suspendues avec leur statut, les
 * champs exacts (linkId, shopName, linkStatus), les permissions et la pagination. L'isolation multi-entreprise est
 * assurée structurellement ({@code TenantContext} + requête SQL bornée aux liaisons de l'entreprise courante).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PriceProposalsIT {

	private static final String CROSS_LINK = "/api/price-proposals";

	private static final String PROPOSALS = "/api/integration/v1/prices/proposals";

	private static final String LINK_ACTIONS = "/api/integration-links/{id}";

	private static final Set<String> PRICING = Set.of("catalog:read", "price:propose");

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

	/** ADMIN : a {@code integration:price-approve} et {@code integration:manage}. */
	private UUID admin;

	/** CAISSIER : n'a pas {@code integration:price-approve}. */
	private UUID cashier;

	/** Rôle personnalisé avec {@code integration:manage} seul : n'a pas {@code integration:price-approve}. */
	private UUID manager;

	/** Rôles personnalisés créés en cours de test, supprimés dans {@link #drop()}. */
	private final List<UUID> createdRoles = new ArrayList<>();

	private final Map<TenantRef, List<UUID[]>> created = new LinkedHashMap<>();

	@BeforeAll
	void provision() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		this.admin = user("ADMIN");
		this.cashier = user("CAISSIER");
		this.manager = userWithPermissionsOnly(Permissions.INTEGRATION_MANAGE);
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
		this.links.cleanUp();
	}

	@AfterAll
	void drop() {
		TenantContext.runAs(this.company, () -> {
			this.jdbc.update("delete from user_role where user_id in (?, ?, ?)", this.admin, this.cashier,
					this.manager);
			this.jdbc.update("delete from refresh_token where user_id in (?, ?, ?)", this.admin, this.cashier,
					this.manager);
			this.jdbc.update("delete from app_user where id in (?, ?, ?)", this.admin, this.cashier, this.manager);
		});
		for (UUID roleId : this.createdRoles) {
			this.jdbc.update("delete from role_permission where role_id = ?", roleId);
			this.jdbc.update("delete from role where id = ?", roleId);
		}
	}

	// --- Permissions -----------------------------------------------------------------------------------------

	@Test
	void listRequiresPriceApprovePermission() throws Exception {
		this.mvc.perform(get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.cashier)))
			.andExpect(status().isForbidden());
		this.mvc.perform(get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isOk());
	}

	@Test
	void unauthenticatedRequestIsRejected() throws Exception {
		this.mvc.perform(get(CROSS_LINK)).andExpect(status().isUnauthorized());
	}

	// --- Liaisons révoquées / suspendues -----------------------------------------------------------------------

	@Test
	void revokedLinksAreExcluded() throws Exception {
		Linked shop = active();
		UUID product = product("1500.00");
		propose(shop, product, "1400.00");

		// Révoquer la liaison via l'API GestStock
		this.mvc.perform(post(LINK_ACTIONS + "/revoke", shop.id())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isOk());

		// Aucune proposition visible : la liaison est révoquée
		JsonNode response = page(this.mvc.perform(
				get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(response.get("total").asLong()).isZero();
		assertThat(response.get("items")).isEmpty();
	}

	@Test
	void suspendedLinksAreIncludedWithSuspendedStatus() throws Exception {
		Linked shop = active();
		UUID product = product("1500.00");
		UUID proposalId = propose(shop, product, "1400.00");

		// Suspendre la liaison
		this.mvc.perform(post(LINK_ACTIONS + "/suspend", shop.id())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isOk());

		// La proposition est visible avec linkStatus = SUSPENDED
		JsonNode response = page(this.mvc.perform(
				get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(response.get("total").asLong()).isEqualTo(1);
		assertThat(response.get("items")).singleElement().satisfies(item -> {
			assertThat(item.get("proposalId").asString()).isEqualTo(proposalId.toString());
			assertThat(item.get("linkStatus").asString()).isEqualTo("SUSPENDED");
		});
	}

	// --- Champs de la réponse ---------------------------------------------------------------------------------

	@Test
	void responseItemContainsAllExpectedFields() throws Exception {
		Linked shop = active();
		UUID product = product("1200.00");
		UUID proposalId = propose(shop, product, "1100.00");

		JsonNode response = page(this.mvc.perform(
				get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(response.get("total").asLong()).isEqualTo(1);
		JsonNode item = response.get("items").get(0);

		// Champs ProposalView
		assertThat(item.get("proposalId").asString()).isEqualTo(proposalId.toString());
		assertThat(item.has("productId")).isTrue();
		assertThat(item.has("productName")).isTrue();
		assertThat(item.has("productSku")).isTrue();
		assertThat(item.get("price").asString()).isEqualTo("1100.00");
		assertThat(item.has("currency")).isTrue();
		assertThat(item.get("status").asString()).isEqualTo("PROPOSED");
		assertThat(item.has("referencePriceAtProposal")).isTrue();
		assertThat(item.has("currentReferencePrice")).isTrue();
		assertThat(item.has("proposedAt")).isTrue();

		// Champs croisés (G24)
		assertThat(item.get("linkId").asString()).isEqualTo(shop.id().toString());
		assertThat(item.get("shopName").asString()).isEqualTo(shop.shopName());
		assertThat(item.get("linkStatus").asString()).isEqualTo("ACTIVE");
	}

	@Test
	void proposalsFromMultipleLinksAreCombined() throws Exception {
		Linked shop1 = active();
		Linked shop2 = active();
		UUID product1 = product("1000.00");
		UUID product2 = product("2000.00");
		propose(shop1, product1, "900.00");
		propose(shop2, product2, "1800.00");

		JsonNode response = page(this.mvc.perform(
				get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(response.get("total").asLong()).isEqualTo(2);
		assertThat(response.get("items")).hasSize(2);
	}

	// --- Statut et pagination ---------------------------------------------------------------------------------

	@Test
	void onlyProposedStatusIsAccepted() throws Exception {
		// Aucun statut (défaut PROPOSED) → 200
		this.mvc.perform(get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isOk());
		// PROPOSED explicite → 200
		this.mvc.perform(get(CROSS_LINK).param("status", "PROPOSED")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isOk());
		// Tout autre statut → 400
		for (String other : List.of("APPROVED", "REJECTED", "SUPERSEDED", "WITHDRAWN", "UNKNOWN")) {
			this.mvc.perform(get(CROSS_LINK).param("status", other)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
		}
	}

	@Test
	void paginationIsRespected() throws Exception {
		Linked shop = active();
		// Trois produits → trois propositions
		for (int i = 0; i < 3; i++) {
			propose(shop, product("1000.00"), "900.00");
		}

		JsonNode page0 = page(this.mvc.perform(get(CROSS_LINK).param("page", "0").param("size", "2")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(page0.get("items")).hasSize(2);
		assertThat(page0.get("total").asLong()).isEqualTo(3);

		JsonNode page1 = page(this.mvc.perform(get(CROSS_LINK).param("page", "1").param("size", "2")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(page1.get("items")).hasSize(1);
	}

	@Test
	void emptyWhenNoProposals() throws Exception {
		JsonNode response = page(this.mvc.perform(
				get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(response.get("total").asLong()).isZero();
		assertThat(response.get("items")).isEmpty();
	}

	// --- Permissions et isolation ------------------------------------------------------------------------------

	@Test
	void integrationManageAloneIsForbidden() throws Exception {
		// integration:manage ne donne pas accès à GET /api/price-proposals (il faut integration:price-approve)
		this.mvc.perform(get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.manager)))
			.andExpect(status().isForbidden());
	}

	@Test
	void proposalFromOrphanLinkIdIsAbsent() throws Exception {
		// Une proposition dont le link_id ne figure pas dans listForCompany n'est pas renvoyée
		UUID orphanLinkId = UUID.randomUUID();
		UUID product = product("1000.00");
		TenantContext.runAs(this.company, () -> {
			this.jdbc.update("insert into channel_price_head (link_id, product_id) values (?, ?)", orphanLinkId,
					product);
			this.jdbc.update(
					"insert into channel_price (id, link_id, product_id, price_version, status, price, currency,"
							+ " based_on_reference_version, reference_price)"
							+ " values (?, ?, ?, 1, 'PROPOSED', 1000.00, 'EUR', 0, 1000.00)",
					UUID.randomUUID(), orphanLinkId, product);
		});

		JsonNode response = page(this.mvc.perform(
				get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(response.get("total").asLong()).isZero();
		assertThat(response.get("items")).isEmpty();
	}

	// --- Structure de la réponse -------------------------------------------------------------------------------

	@Test
	void responseItemContainsExactlyTheExpectedFields() throws Exception {
		Linked shop = active();
		UUID product = product("1200.00");
		propose(shop, product, "1100.00");

		JsonNode response = page(this.mvc.perform(
				get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		JsonNode item = response.get("items").get(0);

		Set<String> actualFields = new HashSet<>();
		item.properties().forEach(e -> actualFields.add(e.getKey()));
		assertThat(actualFields).containsExactlyInAnyOrder("proposalId", "productId", "productName", "productSku",
				"price", "currency", "status", "referencePriceAtProposal", "currentReferencePrice", "note",
				"proposedAt", "decidedAt", "rejectionReason", "linkId", "shopName", "linkStatus");
	}

	@Test
	void stableOrderByProposedAtThenId() throws Exception {
		// Deux propositions au même horodatage → ordre déterministe par id croissant
		Linked shop = active();
		UUID product1 = product("1000.00");
		UUID product2 = product("2000.00");
		java.sql.Timestamp ts = java.sql.Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
		UUID idA = UUID.randomUUID();
		UUID idB = UUID.randomUUID();
		// firstId < secondId en ordre lexicographique (= ordre PostgreSQL pour les UUID)
		UUID firstId = idA.toString().compareTo(idB.toString()) < 0 ? idA : idB;
		UUID secondId = firstId.equals(idA) ? idB : idA;
		UUID firstProduct = firstId.equals(idA) ? product1 : product2;
		UUID secondProduct = firstId.equals(idA) ? product2 : product1;
		TenantContext.runAs(this.company, () -> {
			this.jdbc.update("insert into channel_price_head (link_id, product_id) values (?, ?)", shop.id(),
					product1);
			this.jdbc.update("insert into channel_price_head (link_id, product_id) values (?, ?)", shop.id(),
					product2);
			this.jdbc.update(
					"insert into channel_price (id, link_id, product_id, price_version, status, price, currency,"
							+ " based_on_reference_version, reference_price, proposed_at)"
							+ " values (?, ?, ?, 1, 'PROPOSED', 900.00, ?, 0, 1000.00, ?)",
					firstId, shop.id(), firstProduct, shop.currency(), ts);
			this.jdbc.update(
					"insert into channel_price (id, link_id, product_id, price_version, status, price, currency,"
							+ " based_on_reference_version, reference_price, proposed_at)"
							+ " values (?, ?, ?, 1, 'PROPOSED', 1800.00, ?, 0, 2000.00, ?)",
					secondId, shop.id(), secondProduct, shop.currency(), ts);
		});

		JsonNode response = page(this.mvc.perform(
				get(CROSS_LINK).header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin))));
		assertThat(response.get("total").asLong()).isEqualTo(2);
		assertThat(response.get("items").get(0).get("proposalId").asString()).isEqualTo(firstId.toString());
		assertThat(response.get("items").get(1).get("proposalId").asString()).isEqualTo(secondId.toString());
	}

	@Test
	void pageSizeAboveMaximumIsRejected() throws Exception {
		// Borne maximale de page : 200 (convention PageQuery.MAX_SIZE). Au-delà → 400.
		this.mvc.perform(get(CROSS_LINK).param("size", "201")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken(this.admin)))
			.andExpect(status().isBadRequest());
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private record Linked(UUID id, String token, String shopName, String currency) {
	}

	private Linked active() {
		String shopName = "shop-g24-" + UUID.randomUUID().toString().substring(0, 8);
		Link link = this.links.active(this.links.client(), this.company.companyId(), shopName, PRICING);
		String clientId = this.platform.jdbc()
			.sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId()).query(String.class).single();
		String token = this.tokens.issue(link.id(), clientId, PRICING).value();
		return new Linked(link.id(), token, link.shopName(), link.currency());
	}

	private UUID product(String retailPrice) {
		return TenantContext.callAs(this.company, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values (?) returning id", UUID.class,
					"Cat G24 " + UUID.randomUUID().toString().substring(0, 8));
			UUID product = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, retail_price) values (?, 'Produit G24', ?, ?::numeric) returning id""",
					UUID.class, "G24-" + UUID.randomUUID().toString().substring(0, 12), category, retailPrice);
			this.created.computeIfAbsent(this.company, ignored -> new ArrayList<>()).add(new UUID[] { product, category });
			return product;
		});
	}

	private UUID propose(Linked shop, UUID product, String price) throws Exception {
		String body = "{\"productId\": \"" + product + "\", \"price\": \"" + price + "\", \"currency\": \""
				+ shop.currency() + "\", \"basedOnReferenceVersion\": 0}";
		this.mvc.perform(post(PROPOSALS).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
			.header("Idempotency-Key", "g24-cle-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isCreated());
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

	private UUID user(String role) {
		String username = "g24." + role.toLowerCase() + "." + UUID.randomUUID().toString().substring(0, 6);
		return TenantContext.callAs(this.company, () -> {
			UUID id = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G24', false) returning id",
					UUID.class, username);
			this.jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = ?", id, role);
			return id;
		});
	}

	/**
	 * Crée un utilisateur avec un rôle personnalisé portant exactement les permissions indiquées. Le rôle est suivi
	 * dans {@link #createdRoles} pour suppression dans {@link #drop()}.
	 */
	private UUID userWithPermissionsOnly(String... permissions) {
		// Créer le rôle dans le tenant par défaut (= legacyCompany = this.company)
		String code = "G24R_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		UUID roleId = this.jdbc.queryForObject("insert into role (code, label) values (?, 'Essai G24') returning id",
				UUID.class, code);
		this.createdRoles.add(roleId);
		for (String permission : permissions) {
			this.jdbc.update(
					"insert into role_permission (role_id, permission_id) select ?, id from permission where code = ?",
					roleId, permission);
		}
		String username = "g24r." + UUID.randomUUID().toString().substring(0, 6);
		return TenantContext.callAs(this.company, () -> {
			UUID id = this.jdbc.queryForObject(
					"insert into app_user (username, last_name, must_change_password) values (?, 'Essai G24', false) returning id",
					UUID.class, username);
			this.jdbc.update("insert into user_role (user_id, role_id) values (?, ?)", id, roleId);
			return id;
		});
	}

}
