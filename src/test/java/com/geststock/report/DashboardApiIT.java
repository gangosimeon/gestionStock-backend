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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tableau de bord sur des données réelles d'un dépôt dédié : ventes (dont une annulée), retour,
 * achat reçu en partie, stock. Chaque indicateur est comparé au calcul attendu, écrit à la main
 * dans le test à partir des règles de {@code docs/dashboard.md}.
 *
 * <p>Scénario (aujourd'hui) — monture A (achat 15 000, vente 35 000), lentilles B (4 000 /
 * 10 000, seuil 8), solution C (seuil 2) :
 * <ul>
 * <li>stock initial A 10, B 5, C 1 puis perte de C (rupture) ;</li>
 * <li>commande de 5 B à 4 000 (20 000), 3 reçues (12 000) ;</li>
 * <li>vente 1 : 2 A remisés de 10 % = 63 000 (coût 30 000) ; vente 2 : 1 B = 10 000 (coût
 * 4 000) ; vente 3 : 1 A annulée ;</li>
 * <li>retour d'1 A de la vente 1, revendable, remboursé 31 500 (coût récupéré 15 000).</li>
 * </ul>
 * Attendu : CA brut 73 000, retours 31 500, CA net 41 500, coût 34 000 − 15 000 = 19 000,
 * marge 22 500 (54,2 %), panier moyen 36 500 ; stock A 9, B 7, C 0 = 16 unités valant
 * 9 × 15 000 + 7 × 4 000 = 163 000.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class DashboardApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID depot;

	private UUID frame;

	private UUID lens;

	private String today;

	@BeforeEach
	void setUp() throws Exception {
		this.today = LocalDate.now(ZoneId.of("Africa/Abidjan")).toString();
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('dashboard_user', 'Pilotage') returning id", UUID.class);
		this.depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-DSH', 'Dépôt tableau de bord', 'STORE') returning id",
				UUID.class);
		this.frame = product("DSH-A", 15000, 35000, 0);
		this.lens = product("DSH-B", 4000, 10000, 8);
		UUID solution = product("DSH-C", 2000, 5000, 2);
		move(this.frame, 10, "SUPPLY");
		move(this.lens, 5, "SUPPLY");
		move(solution, 1, "SUPPLY");
		move(solution, -1, "LOSS");

		UUID supplier = this.jdbc.queryForObject(
				"insert into supplier (code, name) values ('FRS-DSH', 'Fournisseur tableau') returning id", UUID.class);
		String order = idOf(perform(post("/api/purchases/orders"), """
				{"supplierId": "%s", "lines": [{"productId": "%s", "quantity": 5, "unitPurchasePrice": 4000}]}"""
			.formatted(supplier, this.lens)));
		perform(post("/api/purchases/orders/{id}/receive", order),
				"{\"lines\": [{\"productId\": \"%s\", \"quantity\": 3}]}".formatted(this.lens))
			.andExpect(status().isOk());

		perform(post("/api/cash-register/open"), "{\"openingBalance\": 100000}").andExpect(status().isCreated());
		String first = idOf(sell("{\"productId\": \"%s\", \"quantity\": 2, \"discountPercent\": 10}".formatted(this.frame)));
		idOf(sell("{\"productId\": \"%s\", \"quantity\": 1}".formatted(this.lens)));
		String cancelled = idOf(sell("{\"productId\": \"%s\", \"quantity\": 1}".formatted(this.frame)));
		perform(post("/api/sales/{id}/cancel", cancelled), "{\"reason\": \"Erreur de saisie\"}")
			.andExpect(status().isOk());
		perform(post("/api/sales/{id}/returns", first), """
				{"settlement": "REFUND", "reason": "Ne convient pas", "items": [
				  {"productId": "%s", "quantity": 1, "condition": "RESELLABLE"}]}""".formatted(this.frame))
			.andExpect(status().isCreated());
	}

	@Test
	void theSummaryIsComputedFromTheDatabase() throws Exception {
		perform(get("/api/dashboard/summary"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.warehouseId").value(this.depot.toString()))
			.andExpect(jsonPath("$.from").value(this.today))
			.andExpect(jsonPath("$.to").value(this.today))
			.andExpect(jsonPath("$.sales.count").value(2))
			.andExpect(jsonPath("$.sales.grossSales").value(73000))
			.andExpect(jsonPath("$.sales.returns").value(31500))
			.andExpect(jsonPath("$.sales.netSales").value(41500))
			.andExpect(jsonPath("$.sales.costOfSales").value(19000))
			.andExpect(jsonPath("$.sales.grossProfit").value(22500))
			.andExpect(jsonPath("$.sales.marginRate").value(54.2))
			.andExpect(jsonPath("$.sales.averageBasket").value(36500))
			.andExpect(jsonPath("$.sales.cancelledCount").value(1))
			.andExpect(jsonPath("$.purchases.receiptsCount").value(1))
			.andExpect(jsonPath("$.purchases.receivedAmount").value(12000))
			.andExpect(jsonPath("$.purchases.ordersCount").value(1))
			.andExpect(jsonPath("$.purchases.orderedAmount").value(20000))
			.andExpect(jsonPath("$.stock.units").value(16))
			.andExpect(jsonPath("$.stock.value").value(163000))
			.andExpect(jsonPath("$.stock.productsInStock").value(2))
			.andExpect(jsonPath("$.stock.outOfStock").value(1))
			.andExpect(jsonPath("$.stock.belowMinimum").value(1))
			.andExpect(jsonPath("$.stock.lowStock").value(0));

		// Une période passée ne contient rien.
		perform(get("/api/dashboard/summary").param("from", "2020-01-01").param("to", "2020-01-31"), null)
			.andExpect(jsonPath("$.sales.count").value(0))
			.andExpect(jsonPath("$.sales.netSales").value(0))
			.andExpect(jsonPath("$.sales.marginRate").doesNotExist())
			.andExpect(jsonPath("$.sales.averageBasket").doesNotExist())
			.andExpect(jsonPath("$.purchases.receivedAmount").value(0));
	}

	@Test
	void seriesCoverEveryDayAndComeFromRealData() throws Exception {
		perform(get("/api/dashboard/sales-by-day").param("days", "3"), null)
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].total").value(0))
			.andExpect(jsonPath("$[0].count").value(0))
			.andExpect(jsonPath("$[2].date").value(this.today))
			.andExpect(jsonPath("$[2].count").value(2))
			.andExpect(jsonPath("$[2].grossSales").value(73000))
			.andExpect(jsonPath("$[2].returns").value(31500))
			.andExpect(jsonPath("$[2].total").value(41500))
			.andExpect(jsonPath("$[2].profit").value(22500));

		// A : 2 vendues − 1 retournée = 1, CA 63 000 − 31 500, marge 31 500 − (30 000 − 15 000).
		perform(get("/api/dashboard/top-products"), null)
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].productId").value(this.frame.toString()))
			.andExpect(jsonPath("$[0].sku").value("DSH-A"))
			.andExpect(jsonPath("$[0].quantity").value(1))
			.andExpect(jsonPath("$[0].total").value(31500))
			.andExpect(jsonPath("$[0].profit").value(16500))
			.andExpect(jsonPath("$[1].productId").value(this.lens.toString()))
			.andExpect(jsonPath("$[1].total").value(10000))
			.andExpect(jsonPath("$[1].profit").value(6000));
		perform(get("/api/dashboard/top-products").param("limit", "1"), null).andExpect(jsonPath("$.length()").value(1));

		// Tout le stock est entré aujourd'hui : 0 les jours précédents, 16 en fin de journée.
		perform(get("/api/dashboard/stock-evolution").param("days", "3"), null)
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[0].stockTotal").value(0))
			.andExpect(jsonPath("$[1].stockTotal").value(0))
			.andExpect(jsonPath("$[2].stockTotal").value(16))
			.andExpect(jsonPath("$[2].date").value(this.today))
			.andExpect(jsonPath("$[2].label").value(this.today.substring(8, 10) + "/" + this.today.substring(5, 7)));
	}

	@Test
	void theFrontDashboardIsServedInOneCall() throws Exception {
		perform(get("/api/dashboard"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kpis.todaySalesTotal").value(41500))
			.andExpect(jsonPath("$.kpis.todayProfit").value(22500))
			.andExpect(jsonPath("$.kpis.stockTotal").value(16))
			.andExpect(jsonPath("$.salesByDay.length()").value(14))
			.andExpect(jsonPath("$.salesByDay[13].total").value(41500))
			.andExpect(jsonPath("$.topProducts.length()").value(2))
			.andExpect(jsonPath("$.stockEvolution.length()").value(7))
			.andExpect(jsonPath("$.stockEvolution[6].stockTotal").value(16))
			.andExpect(jsonPath("$.today.sales.netSales").value(41500));
	}

	@Test
	void accessAndParametersAreControlled() throws Exception {
		RequestPostProcessor seller = authentication(StockFixtures.authentication(this.manager, Permissions.SALE_READ,
				Permissions.STOCK_READ));
		this.mvc.perform(get("/api/dashboard").with(seller)).andExpect(status().isForbidden());

		// Lecteur des rapports sans stock:read : pas de compteurs de ruptures (null), le reste est servi.
		RequestPostProcessor reporter = authentication(StockFixtures.authentication(this.manager, Permissions.REPORT_READ));
		this.mvc.perform(get("/api/dashboard/summary").with(reporter))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stock.outOfStock").doesNotExist())
			.andExpect(jsonPath("$.stock.units").isNumber());
		this.mvc.perform(get("/api/dashboard").with(reporter).param("allWarehouses", "true"))
			.andExpect(status().isForbidden());
		perform(get("/api/dashboard").param("allWarehouses", "true"), null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.warehouseId").doesNotExist());

		perform(get("/api/dashboard/summary").param("from", "2026-02-10").param("to", "2026-02-01"), null)
			.andExpect(status().isBadRequest());
		perform(get("/api/dashboard/sales-by-day").param("days", "400"), null).andExpect(status().isBadRequest());
		perform(get("/api/dashboard/top-products").param("limit", "0"), null).andExpect(status().isBadRequest());
		perform(get("/api/dashboard/summary").param("from", "hier"), null).andExpect(status().isBadRequest());
	}

	@Test
	void aSellerSeesOnlyTheirOwnDayWithoutReportRead() throws Exception {
		// Caissier : sale:create sans report:read. Ses ventes du jour : 63 000 + 10 000, une annulée.
		RequestPostProcessor cashier = authentication(StockFixtures.authentication(this.manager, Permissions.SALE_CREATE,
				Permissions.SALE_READ, Permissions.CASH_OPERATE));
		// Règlement d'une dette ancienne encaissé aujourd'hui par le caissier : compté dans l'encaissé du jour,
		// sans changer le crédit accordé sur ses ventes. (Contrainte différée non forcée : test en rollback.)
		UUID debtor = this.jdbc.queryForObject(
				"insert into customer (code, name) values ('CLI-DASH-ME', 'Client à crédit') returning id", UUID.class);
		this.jdbc.update("""
				insert into customer_account_entry (customer_id, entry_type, direction, amount, balance_after, source_type,
				                                    source_id, created_by)
				values (?, 'PAYMENT', 'CREDIT', 11000, 0, 'CASH_ENTRY', gen_random_uuid(), ?)""", debtor, this.manager);
		this.mvc.perform(get("/api/dashboard/me").with(cashier))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.date").value(this.today))
			.andExpect(jsonPath("$.salesCount").value(2))
			.andExpect(jsonPath("$.salesTotal").value(73000))
			.andExpect(jsonPath("$.paidAmount").value(73000))
			.andExpect(jsonPath("$.creditAmount").value(0))
			.andExpect(jsonPath("$.customerPaymentsAmount").value(11000))
			.andExpect(jsonPath("$.customerPaymentsCount").value(1))
			.andExpect(jsonPath("$.collectedAmount").value(84000))
			.andExpect(jsonPath("$.cancelledCount").value(1))
			.andExpect(jsonPath("$.lastSaleAt").isString())
			// Courbe : 7 jours (aujourd'hui en dernier), ventes validées seulement.
			.andExpect(jsonPath("$.last7Days.length()").value(7))
			.andExpect(jsonPath("$.last7Days[6].date").value(this.today))
			.andExpect(jsonPath("$.last7Days[6].count").value(2))
			.andExpect(jsonPath("$.last7Days[6].total").value(73000))
			.andExpect(jsonPath("$.last7Days[0].profit").doesNotExist())
			// Ni marge ni chiffres du magasin.
			.andExpect(jsonPath("$.profit").doesNotExist());
		this.mvc.perform(get("/api/dashboard").with(cashier)).andExpect(status().isForbidden());

		// Un autre vendeur ne voit que ses propres ventes (aucune).
		UUID colleague = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('dashboard_colleague', 'Collègue') returning id",
				UUID.class);
		this.mvc.perform(get("/api/dashboard/me")
			.with(authentication(StockFixtures.authentication(colleague, Permissions.SALE_CREATE))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.salesCount").value(0))
			.andExpect(jsonPath("$.salesTotal").value(0))
			.andExpect(jsonPath("$.customerPaymentsAmount").value(0))
			.andExpect(jsonPath("$.last7Days.length()").value(7))
			.andExpect(jsonPath("$.last7Days[6].total").value(0))
			.andExpect(jsonPath("$.lastSaleAt").doesNotExist());

		// Sans droit de vendre : refusé.
		this.mvc.perform(get("/api/dashboard/me")
			.with(authentication(StockFixtures.authentication(colleague, Permissions.STOCK_READ))))
			.andExpect(status().isForbidden());
	}

	// --- Outils ------------------------------------------------------------------------------------

	private UUID product(String sku, int purchasePrice, int retailPrice, int threshold) {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price, alert_threshold)
				values (?, ?, (select id from category where name = 'Montures'), ?, ?, ?, ?) returning id""", UUID.class,
				sku, "Produit " + sku, purchasePrice, retailPrice, retailPrice, threshold);
	}

	private void move(UUID productId, int quantity, String reason) throws Exception {
		perform(post("/api/stock/movements"),
				"{\"productId\": \"%s\", \"quantity\": %d, \"reason\": \"%s\"}".formatted(productId, quantity, reason))
			.andExpect(status().isCreated());
	}

	private ResultActions sell(String item) throws Exception {
		return perform(post("/api/sales"), "{\"paymentMethod\": \"CASH\", \"items\": [" + item + "]}");
	}

	/** Requête du gestionnaire (toutes permissions) dans le dépôt de test. */
	private ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
		request.with(authentication(StockFixtures.authentication(this.manager, Permissions.ALL.toArray(String[]::new))))
			.header("X-Warehouse-Id", this.depot.toString());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return this.mvc.perform(request);
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

}
