package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.ResultActions;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routes de gestion des commandes en ligne (lot 3, G28) :
 * {@code POST /api/integration/v1/online-orders} et sous-routes.
 *
 * <ul>
 * <li>Création réussie (stock suffisant) → RESERVED avec réservation.</li>
 * <li>Création (stock insuffisant) → REJECTED_STOCK, pas de réservation.</li>
 * <li>Idempotence : même corps → 200 réponse existante.</li>
 * <li>Idempotence : externalOrderId réutilisé avec autre corps → 409 IDEMPOTENCY_KEY_REUSED.</li>
 * <li>Produit inconnu → 404 PRODUCT_NOT_FOUND.</li>
 * <li>Produit archivé → 422 PRODUCT_ARCHIVED.</li>
 * <li>Produit sans prix approuvé → 422 PRODUCT_NOT_LINKED.</li>
 * <li>Version de prix erronée → 422 PRICE_VERSION_MISMATCH.</li>
 * <li>Lecture par id → 200.</li>
 * <li>Lecture par id (autre liaison) → 404.</li>
 * <li>Lecture par externalOrderId → 200.</li>
 * <li>Annulation RESERVED → CANCELLED, réservation annulée.</li>
 * <li>Annulation CONVERTED → 409 ORDER_NOT_CANCELLABLE.</li>
 * <li>Annulation déjà CANCELLED → 200 (idempotent).</li>
 * <li>Portée manquante → 403 ; sans jeton → 401.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "geststock.platform.provisioning.async=false")
@Import(PostgresTestConfiguration.class)
class OnlineOrderRouteIT {

	private static final String ORDERS = "/api/integration/v1/online-orders";

	private static final Set<String> ORDERS_WRITE = Set.of("catalog:read", "orders:write");

	private static final Set<String> ORDERS_READ = Set.of("catalog:read", "orders:read");

	private static final Set<String> CATALOG_ONLY = Set.of("catalog:read");

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
	private JsonMapper json;

	private TestLinks links;

	private TenantRef company;

	/** Ressources créées : (productId, categoryId). */
	private final Map<TenantRef, List<UUID[]>> createdProducts = new LinkedHashMap<>();

	/** Dépôts créés (warehouseId). */
	private final List<UUID> createdWarehouses = new ArrayList<>();

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
	}

	@AfterEach
	void tearDown() {
		this.createdProducts.forEach((company, rows) -> TenantContext.runAs(company, () -> {
			this.jdbc.update("delete from online_reservation_line");
			this.jdbc.update("delete from online_reservation");
			this.jdbc.update("delete from online_order_line");
			this.jdbc.update("delete from online_order");
			// stock_level et channel_price référencent product : supprimer avant product
			for (UUID[] row : rows) {
				this.jdbc.update("delete from channel_price_head where product_id = ?", row[0]);
				this.jdbc.update("delete from channel_price where product_id = ?", row[0]);
				// stock_level est supprimé via le dépôt dans la boucle des dépôts (ON DELETE CASCADE
				// ou via DELETE explicite ci-dessous)
				this.jdbc.execute("alter table stock_level disable trigger user");
				this.jdbc.update("delete from stock_level where product_id = ?", row[0]);
				this.jdbc.execute("alter table stock_level enable trigger user");
			}
			for (UUID[] row : rows) {
				this.jdbc.update("delete from product where id = ?", row[0]);
				this.jdbc.update("delete from category where id = ?", row[1]);
			}
		}));
		this.createdProducts.clear();
		TenantContext.runAs(this.company, () -> {
			for (UUID wh : this.createdWarehouses) {
				this.jdbc.update("delete from integration_link_warehouse where warehouse_id = ?", wh);
				this.jdbc.update("delete from stock_level where warehouse_id = ?", wh);
				this.jdbc.update("delete from warehouse where id = ?", wh);
			}
		});
		this.createdWarehouses.clear();
		this.links.cleanUp();
	}

	// --- Création réussie (stock OK) -------------------------------------------------------

	@Test
	void createOrderWithSufficientStockReturnsReserved() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		UUID warehouse = warehouse(shop.id(), product, 10);

		String body = orderBody(shop, product, 2, "900.00", "ext-" + uuid());
		ResultActions result = this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));

		JsonNode order = json(result.andExpect(status().isCreated()));
		assertThat(order.get("status").asString()).isEqualTo("RESERVED");
		assertThat(order.has("reservation")).isTrue();
		assertThat(order.get("reservation").get("status").asString()).isEqualTo("RESERVED");
		assertThat(order.get("reservation").get("allocations")).isNotEmpty();
	}

	@Test
	void createOrderWithInsufficientStockReturnsRejectedStock() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		UUID warehouse = warehouse(shop.id(), product, 1); // only 1 in stock

		String body = orderBody(shop, product, 5, "900.00", "ext-" + uuid()); // request 5
		ResultActions result = this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));

		JsonNode order = json(result.andExpect(status().isCreated()));
		assertThat(order.get("status").asString()).isEqualTo("REJECTED_STOCK");
		assertThat(order.has("reservation")).isFalse();
	}

	// --- Idempotence -----------------------------------------------------------------------

	@Test
	void createOrderIdempotentReplayReturnsSameResponse() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		warehouse(shop.id(), product, 10);

		String externalId = "ext-idem-" + uuid();
		String body = orderBody(shop, product, 2, "900.00", externalId);

		// Première requête → 201
		ResultActions first = this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
		JsonNode firstOrder = json(first.andExpect(status().isCreated()));

		// Rejeu identique → 200
		ResultActions replay = this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
		JsonNode replayOrder = json(replay.andExpect(status().isOk()));

		assertThat(replayOrder.get("orderId").asString()).isEqualTo(firstOrder.get("orderId").asString());
	}

	@Test
	void createOrderIdempotentConflictWithDifferentBody() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		warehouse(shop.id(), product, 10);

		String externalId = "ext-conflict-" + uuid();
		String body1 = orderBody(shop, product, 2, "900.00", externalId);
		String body2 = orderBody(shop, product, 3, "900.00", externalId); // different quantity

		this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body1))
			.andExpect(status().isCreated());

		this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body2))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	// --- Validation des produits -----------------------------------------------------------

	@Test
	void createOrderWithUnknownProductReturnsNotFound() throws Exception {
		Linked shop = active(ORDERS_WRITE);

		String body = orderBodyRaw(shop, UUID.randomUUID(), 1, "100.00", 0, "ext-" + uuid());
		this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
	}

	@Test
	void createOrderWithArchivedProductReturnsProductArchived() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		// Archive the product
		TenantContext.runAs(this.company, () -> this.jdbc.update("update product set active = false where id = ?", product));

		String body = orderBodyRaw(shop, product, 1, "900.00", 0, "ext-" + uuid());
		this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("PRODUCT_ARCHIVED"));

		// Restore
		TenantContext.runAs(this.company, () -> this.jdbc.update("update product set active = true where id = ?", product));
	}

	@Test
	void createOrderWithProductNotLinkedReturnsError() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		// No approved price created

		String body = orderBodyRaw(shop, product, 1, "900.00", 0, "ext-" + uuid());
		this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_LINKED"));
	}

	@Test
	void createOrderWithWrongPriceVersionReturnsError() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		long approvedVersion = approvedPriceVersion(shop, product);

		// Use wrong version
		String body = orderBodyRaw(shop, product, 1, "900.00", approvedVersion + 999, "ext-" + uuid());
		this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.code").value("PRICE_VERSION_MISMATCH"));
	}

	// --- Lecture -------------------------------------------------------------------

	@Test
	void getOrderByIdReturns200() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		warehouse(shop.id(), product, 10);

		String externalId = "ext-get-" + uuid();
		String body = orderBody(shop, product, 1, "900.00", externalId);
		JsonNode created = json(this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated()));
		String orderId = created.get("orderId").asString();

		// orders:write implique orders:read (C5) — le jeton de création convient aussi pour la lecture
		this.mvc.perform(get(ORDERS + "/{id}", orderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orderId").value(orderId));
	}

	@Test
	void getOrderByIdFromOtherLinkReturnsNotFound() throws Exception {
		Linked shop1 = active(ORDERS_WRITE);
		Linked shop2 = active(ORDERS_READ);
		UUID product = product("1000.00");
		approvePrice(shop1, product, "900.00");
		warehouse(shop1.id(), product, 10);

		String body = orderBody(shop1, product, 1, "900.00", "ext-" + uuid());
		JsonNode created = json(this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop1.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated()));
		String orderId = created.get("orderId").asString();

		// shop2 cannot read shop1's order
		this.mvc.perform(get(ORDERS + "/{id}", orderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop2.token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
	}

	@Test
	void getOrderByExternalIdReturns200() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		warehouse(shop.id(), product, 10);

		String externalId = "ext-byext-" + uuid();
		String body = orderBody(shop, product, 1, "900.00", externalId);
		this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated());

		// orders:write implique orders:read (C5) — le jeton de création convient aussi pour la lecture
		this.mvc.perform(get(ORDERS + "/external/{externalId}", externalId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.externalOrderId").value(externalId));
	}

	// --- Annulation ----------------------------------------------------------------

	@Test
	void cancelReservedOrderSucceeds() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		warehouse(shop.id(), product, 10);

		String body = orderBody(shop, product, 2, "900.00", "ext-cancel-" + uuid());
		JsonNode created = json(this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated()));
		assertThat(created.get("status").asString()).isEqualTo("RESERVED");
		String orderId = created.get("orderId").asString();

		JsonNode cancelled = json(this.mvc.perform(post(ORDERS + "/{id}/cancel", orderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isOk()));
		assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");

		// Réservation annulée en base
		TenantContext.runAs(this.company, () -> {
			String resStatus = this.jdbc.queryForObject(
					"select r.status from online_reservation r join online_order o on o.id = r.order_id where o.id = ?",
					String.class, UUID.fromString(orderId));
			assertThat(resStatus).isEqualTo("CANCELLED");
		});
	}

	@Test
	void cancelConvertedOrderIsConflict() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		warehouse(shop.id(), product, 10);

		String body = orderBody(shop, product, 1, "900.00", "ext-converted-" + uuid());
		JsonNode created = json(this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated()));
		String orderId = created.get("orderId").asString();

		// Force status to CONVERTED
		TenantContext.runAs(this.company, () -> this.jdbc.update(
				"update online_order set status = 'CONVERTED' where id = ?", UUID.fromString(orderId)));

		this.mvc.perform(post(ORDERS + "/{id}/cancel", orderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELLABLE"));
	}

	@Test
	void cancelAlreadyCancelledOrderIsIdempotent() throws Exception {
		Linked shop = active(ORDERS_WRITE);
		UUID product = product("1000.00");
		approvePrice(shop, product, "900.00");
		warehouse(shop.id(), product, 5);

		String body = orderBody(shop, product, 1, "900.00", "ext-idem-cancel-" + uuid());
		JsonNode created = json(this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isCreated()));
		String orderId = created.get("orderId").asString();

		// First cancel
		this.mvc.perform(post(ORDERS + "/{id}/cancel", orderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));

		// Second cancel: idempotent
		this.mvc.perform(post(ORDERS + "/{id}/cancel", orderId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	// --- Portée et authentification --------------------------------------------------------

	@Test
	void createWithoutOrdersWriteScopeIsForbidden() throws Exception {
		Linked shop = active(CATALOG_ONLY);

		String body = orderBodyRaw(shop, UUID.randomUUID(), 1, "100.00", 0, "ext-" + uuid());
		this.mvc.perform(post(ORDERS)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isForbidden());
	}

	@Test
	void getWithoutOrdersReadScopeIsForbidden() throws Exception {
		Linked shop = active(CATALOG_ONLY);

		this.mvc.perform(get(ORDERS + "/{id}", UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + shop.token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void requestWithoutTokenIsUnauthorized() throws Exception {
		this.mvc.perform(post(ORDERS)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isUnauthorized());

		this.mvc.perform(get(ORDERS + "/{id}", UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
	}

	// ---- Outils ---------------------------------------------------------------------------

	private record Linked(UUID id, String token, String currency) {}

	private Linked active(Set<String> scopes) {
		String shopName = "shop-g28-" + UUID.randomUUID().toString().substring(0, 8);
		Link link = this.links.active(this.links.client(), this.company.companyId(), shopName, scopes);
		String clientId = this.platform.jdbc()
			.sql("select client_id from platform.integration_client where id = :id")
			.param("id", link.clientId())
			.query(String.class)
			.single();
		String token = this.tokens.issue(link.id(), clientId, scopes).value();
		return new Linked(link.id(), token, link.currency());
	}

	/** Crée un Linked avec le même link_id mais un nouveau jeton avec des portées différentes. */
	private Linked activeWithId(UUID linkId, Set<String> scopes) {
		// Pour simplifier les tests de lecture, on crée un nouveau jeton avec les portées demandées
		// pour le même linkId.
		String clientId = this.platform.jdbc()
			.sql("select ci.client_id from platform.integration_client ci "
					+ "join platform.integration_link il on il.client_id = ci.id where il.id = :id")
			.param("id", linkId)
			.query(String.class)
			.single();
		String token = this.tokens.issue(linkId, clientId, scopes).value();
		String currency = this.platform.jdbc()
			.sql("select currency from platform.integration_link where id = :id")
			.param("id", linkId)
			.query(String.class)
			.single();
		return new Linked(linkId, token, currency);
	}

	/** Crée un produit actif avec le prix de référence donné. */
	private UUID product(String retailPrice) {
		return TenantContext.callAs(this.company, () -> {
			UUID category = this.jdbc.queryForObject(
					"insert into category (name) values (?) returning id", UUID.class,
					"Cat G28 " + UUID.randomUUID().toString().substring(0, 8));
			UUID prod = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, retail_price)
					values (?, 'Produit G28', ?, ?::numeric) returning id""",
					UUID.class, "G28-" + UUID.randomUUID().toString().substring(0, 12), category, retailPrice);
			this.createdProducts.computeIfAbsent(this.company, ignored -> new ArrayList<>())
				.add(new UUID[] { prod, category });
			return prod;
		});
	}

	/** Approuve un prix de la liaison pour un produit. */
	private void approvePrice(Linked shop, UUID productId, String price) {
		TenantContext.runAs(this.company, () -> {
			// Insérer channel_price_head si absent
			this.jdbc.update("""
					insert into channel_price_head (link_id, product_id, price_version)
					values (?, ?, 0)
					on conflict (link_id, product_id) do nothing""",
					shop.id(), productId);
			// Avancer la version
			this.jdbc.update("""
					update channel_price_head set price_version = price_version + 1
					where link_id = ? and product_id = ?""",
					shop.id(), productId);
			long version = this.jdbc.queryForObject(
					"select price_version from channel_price_head where link_id = ? and product_id = ?",
					Long.class, shop.id(), productId);
			// Insérer le prix approuvé directement
			this.jdbc.update("""
					insert into channel_price (id, link_id, product_id, price_version, status, price, currency,
					  based_on_reference_version, reference_price, decided_at, approved_reference_price)
					values (gen_random_uuid(), ?, ?, ?, 'APPROVED', ?::numeric, ?,
					  0, ?::numeric, now(), ?::numeric)""",
					shop.id(), productId, version, price, shop.currency(),
					this.jdbc.queryForObject("select retail_price from product where id = ?", String.class, productId),
					this.jdbc.queryForObject("select retail_price from product where id = ?", String.class, productId));
		});
	}

	/** Retourne la version du prix approuvé. */
	private long approvedPriceVersion(Linked shop, UUID productId) {
		return TenantContext.callAs(this.company, () -> this.jdbc.queryForObject(
				"select price_version from channel_price where link_id = ? and product_id = ? and status = 'APPROVED'",
				Long.class, shop.id(), productId));
	}

	/** Crée un dépôt lié à la liaison avec du stock pour un produit. */
	private UUID warehouse(UUID linkId, UUID productId, int quantity) {
		return TenantContext.callAs(this.company, () -> {
			// Code warehouse : uniquement majuscules et chiffres et tirets, commence par [A-Z0-9]
			String whCode = "G28" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
			UUID wh = this.jdbc.queryForObject("""
					insert into warehouse (code, name) values (?, ?) returning id""",
					UUID.class, whCode, "Depot G28 " + whCode);
			this.createdWarehouses.add(wh);
			// added_by = identifiant fictif (UUID.randomUUID() car pas d'utilisateur réel ici)
			this.jdbc.update(
					"insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)",
					linkId, wh, UUID.randomUUID());
			// Contourner le trigger de guard pour insérer directement le stock de test
			this.jdbc.execute("alter table stock_level disable trigger user");
			try {
				this.jdbc.update("""
						insert into stock_level (warehouse_id, product_id, quantity)
						values (?, ?, ?)
						on conflict (warehouse_id, product_id) do update set quantity = excluded.quantity""",
						wh, productId, quantity);
			}
			finally {
				this.jdbc.execute("alter table stock_level enable trigger user");
			}
			return wh;
		});
	}

	/** Corps d'une commande en utilisant les données du produit approuvé. */
	private String orderBody(Linked shop, UUID productId, int quantity, String price, String externalId) {
		long version = TenantContext.callAs(this.company, () -> this.jdbc.queryForObject(
				"select price_version from channel_price where link_id = ? and product_id = ? and status = 'APPROVED'",
				Long.class, shop.id(), productId));
		return orderBodyRaw(shop, productId, quantity, price, version, externalId);
	}

	/** Corps de commande brut. */
	private String orderBodyRaw(Linked shop, UUID productId, int quantity, String price, long priceVersion,
			String externalId) {
		return """
				{
				  "externalOrderId": "%s",
				  "createdAt": "2026-11-06T10:00:00Z",
				  "currency": "%s",
				  "customer": {"name": "Jean Dupont", "phone": "+33612345678"},
				  "delivery": {"mode": "pickup"},
				  "lines": [
				    {
				      "productId": "%s",
				      "quantity": %d,
				      "unitPrice": "%s",
				      "priceVersion": %d
				    }
				  ]
				}""".formatted(externalId, shop.currency(), productId, quantity,
				new BigDecimal(price).setScale(2, RoundingMode.HALF_UP).toPlainString(), priceVersion);
	}

	private JsonNode json(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private static String uuid() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
