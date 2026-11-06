package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Disponibilité indicative par la boutique liée (lot 3, G27) : {@code GET /api/integration/v1/availability}.
 *
 * <ul>
 * <li>Produit en stock : {@code available} correct.</li>
 * <li>Produit sans stock dans les dépôts de la liaison : {@code available = 0}.</li>
 * <li>Produit inconnu : omis de la réponse.</li>
 * <li>Paramètre {@code productIds} absent ou invalide : 400 INVALID_PARAMETER.</li>
 * <li>Plus de 100 identifiants : 400 VALIDATION_FAILED.</li>
 * <li>Portée {@code stock:read} absente : 403.</li>
 * <li>Sans jeton : 401.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
class AvailabilityRouteIT {

	private static final String AVAILABILITY = "/api/integration/v1/availability";

	private static final Set<String> STOCK = Set.of("catalog:read", "stock:read");

	private static final Set<String> NO_STOCK = Set.of("catalog:read");

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
	private TransactionTemplate transactions;

	@Autowired
	private JsonMapper json;

	private TestLinks links;

	private TenantRef company;

	/** Liaisons et entrepôts créés dans ce test, à purger en fin de test. */
	private final List<UUID> createdLinks = new ArrayList<>();

	private final List<UUID> createdWarehouses = new ArrayList<>();

	private final List<UUID> createdProducts = new ArrayList<>();

	private final List<UUID> createdCategories = new ArrayList<>();

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
	}

	@AfterEach
	void tearDown() {
		TenantContext.runAs(this.company, () -> {
			for (UUID linkId : this.createdLinks) {
				this.jdbc.update("delete from integration_link_warehouse where link_id = ?", linkId);
			}
			if (!this.createdWarehouses.isEmpty()) {
				this.transactions.executeWithoutResult(status -> {
					this.jdbc.execute("alter table stock_level disable trigger user");
					this.jdbc.execute("alter table stock_movement disable trigger user");
					for (UUID warehouseId : this.createdWarehouses) {
						this.jdbc.update("delete from stock_level where warehouse_id = ?", warehouseId);
						this.jdbc.update("delete from stock_movement where warehouse_id = ?", warehouseId);
					}
					this.jdbc.execute("alter table stock_movement enable trigger user");
					this.jdbc.execute("alter table stock_level enable trigger user");
				});
			}
			for (UUID productId : this.createdProducts) {
				this.jdbc.update("delete from product where id = ?", productId);
			}
			for (UUID categoryId : this.createdCategories) {
				this.jdbc.update("delete from category where id = ?", categoryId);
			}
			for (UUID warehouseId : this.createdWarehouses) {
				this.jdbc.update("delete from warehouse where id = ?", warehouseId);
			}
		});
		this.createdLinks.clear();
		this.createdWarehouses.clear();
		this.createdProducts.clear();
		this.createdCategories.clear();
		this.links.cleanUp();
	}

	// --- Disponibilité nominale (G27) -------------------------------------------------------------------------

	@Test
	void productInStockReturnsCorrectAvailability() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID warehouse = warehouse();
		linkWarehouse(shop.id(), warehouse);
		setStock(warehouse, product, 10);

		JsonNode response = response(this.mvc.perform(
				get(AVAILABILITY).param("productIds", product.toString())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));

		assertThat(response.get("computedAt")).isNotNull();
		JsonNode items = response.get("items");
		assertThat(items).hasSize(1);
		JsonNode item = items.get(0);
		assertThat(item.get("productId").asString()).isEqualTo(product.toString());
		assertThat(item.get("available").intValue()).isEqualTo(10);
	}

	@Test
	void productWithNoStockReturnsZeroAvailability() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID warehouse = warehouse();
		linkWarehouse(shop.id(), warehouse);
		// no stock inserted

		JsonNode response = response(this.mvc.perform(
				get(AVAILABILITY).param("productIds", product.toString())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));

		JsonNode items = response.get("items");
		assertThat(items).hasSize(1);
		assertThat(items.get(0).get("available").intValue()).isZero();
	}

	@Test
	void unknownProductIsOmittedFromResponse() throws Exception {
		Linked shop = active();
		UUID unknown = UUID.randomUUID();

		JsonNode response = response(this.mvc.perform(
				get(AVAILABILITY).param("productIds", unknown.toString())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));

		assertThat(response.get("items")).isEmpty();
	}

	@Test
	void linkWithoutWarehousesSeesZeroStock() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		// no warehouse associated with link

		JsonNode response = response(this.mvc.perform(
				get(AVAILABILITY).param("productIds", product.toString())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));

		JsonNode items = response.get("items");
		assertThat(items).hasSize(1);
		assertThat(items.get(0).get("available").intValue()).isZero();
	}

	@Test
	void multipleProductsReturnsCorrectAvailabilities() throws Exception {
		Linked shop = active();
		UUID product1 = product("5000.00");
		UUID product2 = product("6000.00");
		UUID warehouse = warehouse();
		linkWarehouse(shop.id(), warehouse);
		setStock(warehouse, product1, 3);
		setStock(warehouse, product2, 7);

		JsonNode response = response(this.mvc.perform(
				get(AVAILABILITY).param("productIds", product1 + "," + product2)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));

		JsonNode items = response.get("items");
		assertThat(items).hasSize(2);
		// Chaque produit dans la réponse
		int found = 0;
		for (int i = 0; i < items.size(); i++) {
			JsonNode item = items.get(i);
			String id = item.get("productId").asString();
			if (id.equals(product1.toString())) {
				assertThat(item.get("available").intValue()).isEqualTo(3);
				found++;
			}
			else if (id.equals(product2.toString())) {
				assertThat(item.get("available").intValue()).isEqualTo(7);
				found++;
			}
		}
		assertThat(found).isEqualTo(2);
	}

	@Test
	void responseContainsExpectedFields() throws Exception {
		Linked shop = active();
		UUID product = product("5000.00");
		UUID warehouse = warehouse();
		linkWarehouse(shop.id(), warehouse);
		setStock(warehouse, product, 5);

		JsonNode response = response(this.mvc.perform(
				get(AVAILABILITY).param("productIds", product.toString())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())));

		assertThat(response.get("computedAt")).isNotNull();
		assertThat(response.get("items")).isNotNull();
		JsonNode item = response.get("items").get(0);
		assertThat(item.get("productId")).isNotNull();
		assertThat(item.get("available")).isNotNull();
		assertThat(item.get("perWarehouse")).isNotNull();
		assertThat(item.get("computedAt")).isNotNull();
	}

	// --- Isolation inter-liaisons (G27) -----------------------------------------------------------------------

	@Test
	void shopOnlySeesStockOfItsOwnWarehouses() throws Exception {
		Linked shop1 = active();
		Linked shop2 = active();
		UUID product = product("5000.00");
		UUID warehouse1 = warehouse();
		UUID warehouse2 = warehouse();
		linkWarehouse(shop1.id(), warehouse1);
		linkWarehouse(shop2.id(), warehouse2);
		setStock(warehouse1, product, 10);
		setStock(warehouse2, product, 3);

		JsonNode page1 = response(this.mvc.perform(
				get(AVAILABILITY).param("productIds", product.toString())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop1.token())));
		assertThat(page1.get("items").get(0).get("available").intValue()).isEqualTo(10);

		JsonNode page2 = response(this.mvc.perform(
				get(AVAILABILITY).param("productIds", product.toString())
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop2.token())));
		assertThat(page2.get("items").get(0).get("available").intValue()).isEqualTo(3);
	}

	// --- Validation des paramètres (G27) -----------------------------------------------------------------------

	@Test
	void productIdsRequired() throws Exception {
		Linked shop = active();
		this.mvc.perform(get(AVAILABILITY).header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	@Test
	void invalidProductIdRejected() throws Exception {
		Linked shop = active();
		this.mvc.perform(get(AVAILABILITY).param("productIds", "not-a-uuid")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	@Test
	void moreThan100ProductIdsRejected() throws Exception {
		Linked shop = active();
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 101; i++) {
			ids.add(UUID.randomUUID().toString());
		}
		this.mvc.perform(get(AVAILABILITY).param("productIds", String.join(",", ids))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// --- Portée et authentification (G27) -----------------------------------------------------------------------

	@Test
	void availabilityWithoutStockReadScope() throws Exception {
		Linked shop = active(NO_STOCK);
		this.mvc.perform(get(AVAILABILITY).param("productIds", UUID.randomUUID().toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void availabilityWithoutTokenIsUnauthorized() throws Exception {
		this.mvc.perform(get(AVAILABILITY).param("productIds", UUID.randomUUID().toString()))
			.andExpect(status().isUnauthorized());
	}

	// --- Outils --------------------------------------------------------------------------------------

	private record Linked(UUID id, String token) {
	}

	private Linked active() {
		return active(STOCK);
	}

	private Linked active(Set<String> scopes) {
		String shopName = "shop-g27-" + UUID.randomUUID().toString().substring(0, 8);
		Link link = this.links.active(this.links.client(), this.company.companyId(), shopName, scopes);
		String clientId = this.platform.jdbc()
			.sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId())
			.query(String.class)
			.single();
		String token = this.tokens.issue(link.id(), clientId, scopes).value();
		this.createdLinks.add(link.id());
		return new Linked(link.id(), token);
	}

	private UUID product(String retailPrice) {
		return TenantContext.callAs(this.company, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values (?) returning id", UUID.class,
					"Cat G27 " + UUID.randomUUID().toString().substring(0, 8));
			this.createdCategories.add(category);
			UUID prod = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, retail_price)
					values (?, 'Produit G27', ?, ?::numeric) returning id""",
					UUID.class, "G27-" + UUID.randomUUID().toString().substring(0, 12), category, retailPrice);
			this.createdProducts.add(prod);
			return prod;
		});
	}

	private UUID warehouse() {
		return TenantContext.callAs(this.company, () -> {
			String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
			UUID id = this.jdbc.queryForObject(
					"insert into warehouse (code, name, type) values (?, ?, 'STORE') returning id",
					UUID.class, "G27-WH-" + suffix, "Dépôt G27 " + suffix);
			this.createdWarehouses.add(id);
			return id;
		});
	}

	private void linkWarehouse(UUID linkId, UUID warehouseId) {
		TenantContext.runAs(this.company, () -> this.jdbc.update(
				"insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)",
				linkId, warehouseId, UUID.randomUUID()));
	}

	private void setStock(UUID warehouseId, UUID productId, int quantity) {
		TenantContext.runAs(this.company, () -> this.transactions.executeWithoutResult(status -> {
			this.jdbc.execute("alter table stock_level disable trigger user");
			this.jdbc.update(
					"insert into stock_level (warehouse_id, product_id, quantity) values (?, ?, ?)"
							+ " on conflict (warehouse_id, product_id) do update set quantity = excluded.quantity",
					warehouseId, productId, quantity);
			this.jdbc.execute("alter table stock_level enable trigger user");
		}));
	}

	private JsonNode response(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8));
	}

}
