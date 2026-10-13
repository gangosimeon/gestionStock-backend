package com.geststock.report;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rapports sur l'activité réelle d'un dépôt dédié, chiffres attendus calculés à la main.
 *
 * <p>Scénario (aujourd'hui) — monture A (Montures, achat 15 000, vente 35 000), solution B
 * (catégorie dédiée, 4 000 / 10 000, seuil 8) :
 * <ul>
 * <li>entrées manuelles A +10, B +5 ; commande de 5 B à 4 000 (20 000), 3 reçues (12 000) ;</li>
 * <li>caisse ouverte avec 50 000 ; vente 1 : 2 A remisés de 10 % = 63 000 (coût 30 000) ;
 * vente 2 : 1 B à crédit pour le client K = 10 000 (coût 4 000) ; vente 3 : 1 A, annulée ;</li>
 * <li>retour d'1 A de la vente 1 (revendable, remboursé 31 500) ; règlement de K : 4 000 ;</li>
 * <li>clôture : attendu 50 000 + 31 500 + 4 000 = 85 500, compté 85 000 (écart −500) ;</li>
 * <li>inventaire de B : 7 en théorie, 6 comptées (−1, soit −4 000).</li>
 * </ul>
 * Stock final : A 9, B 6.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class ReportApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID depot;

	private UUID category;

	private UUID frame;

	private UUID solution;

	private UUID customer;

	private UUID supplier;

	@BeforeEach
	void setUp() throws Exception {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, first_name, last_name) values ('report_user', 'Ama', 'Diallo') returning id",
				UUID.class);
		this.depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-RPT', 'Dépôt rapports', 'STORE') returning id",
				UUID.class);
		this.category = this.jdbc.queryForObject(
				"insert into category (name) values ('Solutions RPT') returning id", UUID.class);
		this.frame = product("RPT-A", this.jdbc.queryForObject("select id from category where name = 'Montures'",
				UUID.class), 15000, 35000, 0);
		this.solution = product("RPT-B", this.category, 4000, 10000, 8);
		this.customer = this.jdbc.queryForObject(
				"insert into customer (code, name, credit_limit) values ('CLI-RPT', 'Client K', 100000) returning id",
				UUID.class);
		this.supplier = this.jdbc.queryForObject(
				"insert into supplier (code, name) values ('FRS-RPT', 'Fournisseur rapports') returning id", UUID.class);

		move(this.frame, 10);
		move(this.solution, 5);
		String order = idOf(perform(post("/api/purchases/orders"), """
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": 5, "unitPurchasePrice": 4000}]}"""
			.formatted(this.supplier, this.solution)));
		ok(perform(post("/api/purchases/orders/{id}/receive", order),
				"{\"lines\": [{\"productId\": \"%s\", \"quantity\": 3}]}".formatted(this.solution)));

		created(perform(post("/api/cash-register/open"), "{\"openingBalance\": 50000}"));
		String first = idOf(perform(post("/api/sales"), """
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 2, "discountPercent": 10}]}"""
			.formatted(this.frame)));
		idOf(perform(post("/api/sales"), """
				{"customerId": "%s", "paidAmount": 0, "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(this.customer, this.solution)));
		String cancelled = idOf(perform(post("/api/sales"), """
				{"paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}""".formatted(this.frame)));
		ok(perform(post("/api/sales/{id}/cancel", cancelled), "{\"reason\": \"Erreur\"}"));
		created(perform(post("/api/sales/{id}/returns", first), """
				{"settlement": "REFUND", "reason": "Rendu", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.frame)));
		created(perform(post("/api/customers/{id}/payments", this.customer),
				"{\"amount\": 4000, \"paymentMethod\": \"CASH\"}"));
		ok(perform(post("/api/cash-register/close"), "{\"countedCash\": 85000}"));

		String inventory = idOf(perform(post("/api/inventories"),
				"{\"scope\": \"SELECTION\", \"productIds\": [\"%s\"]}".formatted(this.solution)));
		ok(perform(post("/api/inventories/{id}/start", inventory), null));
		ok(perform(put("/api/inventories/{id}/counts", inventory),
				"{\"counts\": [{\"productId\": \"%s\", \"countedQuantity\": 6}]}".formatted(this.solution)));
		ok(perform(post("/api/inventories/{id}/submit", inventory), null));
		ok(perform(post("/api/inventories/{id}/validate", inventory), null));
	}

	// --- Ventes --------------------------------------------------------------------------------------

	@Test
	void salesListFiltersPaginatesAndTotalsTheWholeFilter() throws Exception {
		perform(get("/api/reports/sales"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[0].sellerName").value("Ama Diallo"))
			.andExpect(jsonPath("$.summary.completedCount").value(2))
			.andExpect(jsonPath("$.summary.cancelledCount").value(1))
			.andExpect(jsonPath("$.summary.total").value(73000))
			.andExpect(jsonPath("$.summary.cost").value(34000))
			.andExpect(jsonPath("$.summary.returnedAmount").value(31500))
			.andExpect(jsonPath("$.summary.netSales").value(41500))
			.andExpect(jsonPath("$.summary.creditAmount").value(10000));

		// Une page de 1 : les totaux portent toujours sur les 3 ventes.
		perform(get("/api/reports/sales").param("size", "1").param("sort", "total,asc"), null)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.totalPages").value(3))
			.andExpect(jsonPath("$.items[0].total").value(10000))
			.andExpect(jsonPath("$.summary.total").value(73000));
		perform(get("/api/reports/sales").param("size", "1").param("page", "2").param("sort", "total,asc"), null)
			.andExpect(jsonPath("$.items[0].total").value(63000))
			.andExpect(jsonPath("$.items[0].returnedAmount").value(31500))
			.andExpect(jsonPath("$.items[0].netAmount").value(31500));

		perform(get("/api/reports/sales").param("status", "cancelled"), null).andExpect(jsonPath("$.total").value(1));
		perform(get("/api/reports/sales").param("productId", this.solution.toString()), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].customerName").value("Client K"));
		perform(get("/api/reports/sales").param("categoryId", this.category.toString()), null)
			.andExpect(jsonPath("$.total").value(1));
		perform(get("/api/reports/sales").param("customerId", this.customer.toString()), null)
			.andExpect(jsonPath("$.total").value(1));
		perform(get("/api/reports/sales").param("userId", UUID.randomUUID().toString()), null)
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.summary.total").value(0));
		perform(get("/api/reports/sales").param("from", "2020-01-01").param("to", "2020-01-31"), null)
			.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void salesByProductPeriodSellerAndWarehouseAreNetOfReturns() throws Exception {
		perform(get("/api/reports/sales/by-product"), null)
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].sku").value("RPT-A"))
			.andExpect(jsonPath("$.items[0].soldQuantity").value(2))
			.andExpect(jsonPath("$.items[0].returnedQuantity").value(1))
			.andExpect(jsonPath("$.items[0].netQuantity").value(1))
			.andExpect(jsonPath("$.items[0].netRevenue").value(31500))
			.andExpect(jsonPath("$.items[0].cost").value(15000))
			.andExpect(jsonPath("$.items[0].profit").value(16500))
			.andExpect(jsonPath("$.items[0].marginRate").value(52.4))
			.andExpect(jsonPath("$.items[1].categoryName").value("Solutions RPT"))
			.andExpect(jsonPath("$.summary.netRevenue").value(41500))
			.andExpect(jsonPath("$.summary.profit").value(22500));
		perform(get("/api/reports/sales/by-product").param("categoryId", this.category.toString()), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].profit").value(6000));
		perform(get("/api/reports/sales/by-product").param("q", "rpt-a"), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].sku").value("RPT-A"));

		String today = LocalDate.now(ZoneId.of("Africa/Abidjan")).toString();
		perform(get("/api/reports/sales/by-period").param("from", today).param("to", today), null)
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].period").value(today))
			.andExpect(jsonPath("$[0].count").value(2))
			.andExpect(jsonPath("$[0].netSales").value(41500))
			.andExpect(jsonPath("$[0].cost").value(19000))
			.andExpect(jsonPath("$[0].profit").value(22500));
		perform(get("/api/reports/sales/by-period").param("groupBy", "MONTH").param("from", today).param("to", today),
				null)
			.andExpect(jsonPath("$[0].period").value(today.substring(0, 8) + "01"))
			.andExpect(jsonPath("$[0].netSales").value(41500));
		perform(get("/api/reports/sales/by-period").param("days", "3"), null)
			.andExpect(jsonPath("$.length()").value(30));

		perform(get("/api/reports/sales/by-seller"), null)
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].id").value(this.manager.toString()))
			.andExpect(jsonPath("$[0].label").value("Ama Diallo"))
			.andExpect(jsonPath("$[0].count").value(2))
			.andExpect(jsonPath("$[0].netSales").value(41500))
			.andExpect(jsonPath("$[0].profit").value(22500))
			.andExpect(jsonPath("$[0].averageBasket").value(36500));
		perform(get("/api/reports/sales/by-warehouse"), null)
			.andExpect(jsonPath("$[0].label").value("Dépôt rapports"))
			.andExpect(jsonPath("$[0].netSales").value(41500));
	}

	// --- Achats, fournisseurs ------------------------------------------------------------------------

	@Test
	void purchasesAndSuppliers() throws Exception {
		perform(get("/api/reports/purchases"), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].status").value("PARTIALLY_RECEIVED"))
			.andExpect(jsonPath("$.items[0].supplierName").value("Fournisseur rapports"))
			.andExpect(jsonPath("$.items[0].totalAmount").value(20000))
			.andExpect(jsonPath("$.items[0].receivedAmount").value(12000))
			.andExpect(jsonPath("$.items[0].remainingAmount").value(8000))
			.andExpect(jsonPath("$.items[0].overdue").value(false))
			.andExpect(jsonPath("$.summary.remainingAmount").value(8000));
		perform(get("/api/reports/purchases").param("status", "RECEIVED"), null).andExpect(jsonPath("$.total").value(0));
		perform(get("/api/reports/purchases").param("productId", this.frame.toString()), null)
			.andExpect(jsonPath("$.total").value(0));

		perform(get("/api/reports/suppliers").param("supplierId", this.supplier.toString()), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].ordersCount").value(1))
			.andExpect(jsonPath("$.items[0].orderedAmount").value(20000))
			.andExpect(jsonPath("$.items[0].receiptsCount").value(1))
			.andExpect(jsonPath("$.items[0].receivedAmount").value(12000))
			.andExpect(jsonPath("$.items[0].openRemainingAmount").value(8000))
			.andExpect(jsonPath("$.items[0].overdueOrders").value(0))
			.andExpect(jsonPath("$.items[0].lastReceiptAt").isString());
	}

	// --- Stock ---------------------------------------------------------------------------------------

	@Test
	void stockValuationRotationAndMovements() throws Exception {
		perform(get("/api/reports/stock").param("stockState", "IN_STOCK"), null)
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].sku").value("RPT-A"))
			.andExpect(jsonPath("$.items[0].quantity").value(9))
			.andExpect(jsonPath("$.items[0].value").value(135000))
			.andExpect(jsonPath("$.items[0].soldQuantity").value(1))
			.andExpect(jsonPath("$.items[0].coverageDays").value(270.0))
			.andExpect(jsonPath("$.items[0].lastExitAt").isString())
			.andExpect(jsonPath("$.items[1].quantity").value(6))
			.andExpect(jsonPath("$.items[1].value").value(24000))
			.andExpect(jsonPath("$.summary.units").value(15))
			.andExpect(jsonPath("$.summary.value").value(159000));
		perform(get("/api/reports/stock").param("stockState", "BELOW_MINIMUM"), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].sku").value("RPT-B"));
		perform(get("/api/reports/stock").param("categoryId", this.category.toString()), null)
			.andExpect(jsonPath("$.total").value(1));

		// A : +10, −2, −1, +1 (annulation), +1 (retour) ; B : +5, +3, −1, −1 (inventaire).
		perform(get("/api/reports/stock-movements"), null)
			.andExpect(jsonPath("$.total").value(9))
			.andExpect(jsonPath("$.summary.quantityIn").value(20))
			.andExpect(jsonPath("$.summary.quantityOut").value(5))
			.andExpect(jsonPath("$.summary.valueIn").value(212000))
			.andExpect(jsonPath("$.summary.valueOut").value(53000))
			.andExpect(jsonPath("$.items[0].userName").value("Ama Diallo"));
		perform(get("/api/reports/stock-movements").param("type", "SALE").param("productId", this.frame.toString()),
				null)
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.summary.quantityOut").value(3));
		perform(get("/api/reports/stock-movements").param("categoryId", this.category.toString()), null)
			.andExpect(jsonPath("$.total").value(4));
		perform(get("/api/reports/stock-movements/by-type"), null)
			.andExpect(jsonPath("$[?(@.type == 'RECEIPT')].quantity").value(18))
			.andExpect(jsonPath("$[?(@.type == 'SALE')].count").value(3))
			.andExpect(jsonPath("$[?(@.type == 'INVENTORY')].value").value(org.hamcrest.Matchers.hasItem(-4000.0)));
	}

	// --- Inventaires, caisse, clients ----------------------------------------------------------------

	@Test
	void inventoriesCashSessionsAndCustomers() throws Exception {
		perform(get("/api/reports/inventories"), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
			.andExpect(jsonPath("$.items[0].lines").value(1))
			.andExpect(jsonPath("$.items[0].gapLines").value(1))
			.andExpect(jsonPath("$.items[0].quantityLoss").value(1))
			.andExpect(jsonPath("$.items[0].valueDifference").value(-4000))
			.andExpect(jsonPath("$.items[0].validatedByName").value("Ama Diallo"))
			.andExpect(jsonPath("$.summary.completed").value(1));
		perform(get("/api/reports/inventories").param("status", "DRAFT"), null).andExpect(jsonPath("$.total").value(0));

		perform(get("/api/reports/cash-sessions"), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].status").value("CLOSED"))
			.andExpect(jsonPath("$.items[0].openingBalance").value(50000))
			.andExpect(jsonPath("$.items[0].cashSales").value(31500))
			.andExpect(jsonPath("$.items[0].cashIn").value(4000))
			.andExpect(jsonPath("$.items[0].cashOut").value(0))
			.andExpect(jsonPath("$.items[0].expectedCash").value(85500))
			.andExpect(jsonPath("$.items[0].countedCash").value(85000))
			.andExpect(jsonPath("$.items[0].difference").value(-500))
			.andExpect(jsonPath("$.summary.totalDifference").value(-500))
			.andExpect(jsonPath("$.summary.sessionsWithDifference").value(1));
		perform(get("/api/reports/cash-sessions").param("userId", this.manager.toString()).param("status", "OPEN"), null)
			.andExpect(jsonPath("$.total").value(0));

		perform(get("/api/reports/customers").param("customerId", this.customer.toString()), null)
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].balance").value(6000))
			.andExpect(jsonPath("$.items[0].overdueAmount").value(0))
			.andExpect(jsonPath("$.items[0].paymentTermsDays").value(30))
			.andExpect(jsonPath("$.items[0].salesCount").value(1))
			.andExpect(jsonPath("$.items[0].salesAmount").value(10000))
			.andExpect(jsonPath("$.items[0].paymentsAmount").value(4000))
			.andExpect(jsonPath("$.summary.debtors").value(1));
	}

	// --- Droits et paramètres -----------------------------------------------------------------------

	@Test
	void accessAndParametersAreControlled() throws Exception {
		this.mvc.perform(get("/api/reports/sales")
			.with(authentication(StockFixtures.authentication(this.manager, Permissions.SALE_READ))))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/reports/sales").param("allWarehouses", "true")
			.with(authentication(StockFixtures.authentication(this.manager, Permissions.REPORT_READ))))
			.andExpect(status().isForbidden());
		perform(get("/api/reports/sales").param("allWarehouses", "true"), null).andExpect(status().isOk());

		perform(get("/api/reports/sales").param("status", "PAID"), null).andExpect(status().isBadRequest());
		perform(get("/api/reports/sales").param("sort", "password"), null).andExpect(status().isBadRequest());
		perform(get("/api/reports/sales").param("size", "500"), null).andExpect(status().isBadRequest());
		perform(get("/api/reports/sales/by-period").param("groupBy", "WEEK"), null).andExpect(status().isBadRequest());
		perform(get("/api/reports/sales/by-period").param("from", "2024-01-01").param("to", "2026-01-01"), null)
			.andExpect(status().isBadRequest());
		perform(get("/api/reports/stock").param("stockState", "LOW"), null).andExpect(status().isBadRequest());
		perform(get("/api/reports/stock-movements").param("type", "THEFT"), null).andExpect(status().isBadRequest());
		perform(get("/api/reports/sales").param("from", "2026-02-10").param("to", "2026-02-01"), null)
			.andExpect(status().isBadRequest());
	}

	// --- Outils ------------------------------------------------------------------------------------

	private UUID product(String sku, UUID categoryId, int purchasePrice, int retailPrice, int threshold) {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price, alert_threshold)
				values (?, ?, ?, ?, ?, ?, ?) returning id""", UUID.class, sku, "Produit " + sku, categoryId, purchasePrice,
				retailPrice, retailPrice, threshold);
	}

	private void move(UUID productId, int quantity) throws Exception {
		created(perform(post("/api/stock/movements"),
				"{\"productId\": \"%s\", \"quantity\": %d, \"reason\": \"SUPPLY\"}".formatted(productId, quantity)));
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
		request.with(authentication(StockFixtures.authentication(this.manager, Permissions.ALL.toArray(String[]::new))))
			.header("X-Warehouse-Id", this.depot.toString());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return this.mvc.perform(request);
	}

	private static void ok(ResultActions result) throws Exception {
		result.andExpect(status().isOk());
	}

	private static void created(ResultActions result) throws Exception {
		result.andExpect(status().isCreated());
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

}
