package com.geststock.quote;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Devis de bout en bout sur PostgreSQL : création à partir du catalogue (exemple ONG HELP,
 * 952 000 FCFA calculés par le serveur), champs facultatifs absents, aucun mouvement de stock,
 * modification, cycle de vie, droits, isolation des dépôts, facturation par une vente (une seule
 * fois). Chaque test s'exécute dans une transaction annulée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class QuoteApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	private UUID main;

	private UUID depot;

	private UUID cement;

	private UUID iron;

	private UUID wire;

	private UUID customer;

	@BeforeEach
	void setUp() throws Exception {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('quote_api_user', 'Commercial') returning id", UUID.class);
		this.main = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		this.depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-DEV', 'Dépôt devis', 'WAREHOUSE') returning id",
				UUID.class);
		this.cement = product("DEV-CIM-50", "Sac de ciment 50 kg CPA 45", 5000, 6500);
		this.iron = product("DEV-FER-10", "Barre de fer diamètre 10", 4500, 5800);
		this.wire = product("DEV-FIL-10", "Rouleau de 10 m de fil de fer mou", 400, 650);
		this.customer = this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit, price_category)
				values ('CLI-DEV-HELP', 'ONG HELP', 2000000, 'RETAIL') returning id""", UUID.class);
	}

	// --- Création -----------------------------------------------------------------------------------

	@Test
	void theExampleQuoteIsComputedByTheServerAndMovesNoStock() throws Exception {
		int movements = count("stock_movement");
		int sales = count("sale");

		JsonNode quote = read(create(example()).andExpect(status().isCreated()));

		assertThat(quote.get("number").asString()).matches("DEV-\\d{4}-\\d{6}");
		assertThat(quote.get("status").asString()).isEqualTo("DRAFT");
		assertThat(quote.get("quoteDate").asString()).isEqualTo("2022-03-23");
		assertThat(quote.get("customerName").asString()).isEqualTo("ONG HELP");
		assertThat(quote.get("subject").asString()).startsWith("Fourniture des matériels de latrine");
		assertThat(quote.get("lotReference").asString()).isEqualTo("2");
		assertThat(quote.get("beneficiaries").asInt()).isEqualTo(20);
		assertThat(quote.get("warehouseId").asString()).isEqualTo(this.main.toString());
		assertThat(quote.get("total").decimalValue()).isEqualByComparingTo("952000");
		assertThat(quote.get("lines")).hasSize(3);
		JsonNode first = quote.get("lines").get(0);
		assertThat(first.get("productName").asString()).isEqualTo("Sac de ciment 50 kg CPA 45");
		assertThat(first.get("unitPrice").decimalValue()).isEqualByComparingTo("6000");
		assertThat(first.get("lineTotal").decimalValue()).isEqualByComparingTo("720000");
		assertThat(quote.get("lines").get(2).get("lineTotal").decimalValue()).isEqualByComparingTo("12000");

		// Proposition commerciale : ni stock, ni vente, ni caisse.
		assertThat(count("stock_movement")).isEqualTo(movements);
		assertThat(count("sale")).isEqualTo(sales);
		assertThat(this.jdbc.queryForObject("select count(*) from audit_log where entity_type = 'QUOTE' and action = 'CREATE'"
				+ " and user_id = ? and status = 'SUCCESS'", Integer.class, this.manager)).isEqualTo(1);
	}

	@Test
	void optionalFieldsLeftEmptyAreAbsentNotBlank() throws Exception {
		JsonNode quote = read(create("""
				{"customerId": "%s", "subject": "   ", "lotReference": "", "notes": null,
				 "lines": [{"productId": "%s", "quantity": 1, "unitPrice": 6000}]}""".formatted(this.customer, this.cement))
			.andExpect(status().isCreated()));

		for (String field : new String[] { "subject", "lotReference", "beneficiaries", "deliveryAddress",
				"contractReference", "deliveryTerms", "paymentTerms", "executionDelay", "notes", "validUntil" }) {
			assertThat(quote.get(field).isNull()).as(field).isTrue();
		}
		// Sans date : aujourd'hui.
		assertThat(quote.get("quoteDate").asString()).isNotBlank();
	}

	@Test
	void invalidQuotesAreRejected() throws Exception {
		// Client et produits obligatoires.
		create("{\"lines\": [{\"productId\": \"%s\", \"quantity\": 1, \"unitPrice\": 1}]}".formatted(this.cement))
			.andExpect(status().isBadRequest());
		create("{\"customerId\": \"%s\", \"lines\": []}".formatted(this.customer)).andExpect(status().isBadRequest());
		// Prix nul, quantité nulle, validité avant la date, bénéficiaires à 0.
		create(line(this.cement, 1, "0")).andExpect(status().isBadRequest());
		create(line(this.cement, 0, "10")).andExpect(status().isBadRequest());
		create("""
				{"customerId": "%s", "quoteDate": "2026-03-10", "validUntil": "2026-03-01",
				 "lines": [{"productId": "%s", "quantity": 1, "unitPrice": 10}]}""".formatted(this.customer, this.cement))
			.andExpect(status().isBadRequest());
		create("""
				{"customerId": "%s", "beneficiaries": 0,
				 "lines": [{"productId": "%s", "quantity": 1, "unitPrice": 10}]}""".formatted(this.customer, this.cement))
			.andExpect(status().isBadRequest());
		// Produit inconnu : jamais créé à la volée.
		create(line(UUID.randomUUID(), 1, "10")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
		// Produit en double.
		create("""
				{"customerId": "%s", "lines": [{"productId": "%s", "quantity": 1, "unitPrice": 10},
				 {"productId": "%s", "quantity": 2, "unitPrice": 10}]}""".formatted(this.customer, this.cement, this.cement))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("QUOTE_DUPLICATE_PRODUCT"));
		// Produit désactivé.
		this.jdbc.update("update product set active = false where id = ?", this.wire);
		create(line(this.wire, 1, "10")).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"));
		assertThat(count("quote")).isZero();
	}

	@Test
	void aSavedQuoteKeepsItsLabelsAndPricesWhenTheCatalogueChanges() throws Exception {
		String id = id(create(example()));
		this.jdbc.update("update product set name = 'Ciment renommé', retail_price = 9999 where id = ?", this.cement);

		this.mvc.perform(get("/api/quotes/{id}", id).with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[0].productName").value("Sac de ciment 50 kg CPA 45"))
			.andExpect(jsonPath("$.lines[0].unitPrice").value(6000))
			.andExpect(jsonPath("$.total").value(952000));
	}

	// --- Modification et cycle de vie ---------------------------------------------------------------

	@Test
	void draftsAndIssuedQuotesAreEditableTheTotalIsRecomputed() throws Exception {
		String id = id(create(example()));

		this.mvc.perform(put("/api/quotes/{id}", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"customerId": "%s", "quoteDate": "2022-03-24", "subject": "Objet révisé",
					 "lines": [{"productId": "%s", "quantity": 100, "unitPrice": 6000},
					           {"productId": "%s", "quantity": 10, "unitPrice": 600}]}"""
				.formatted(this.customer, this.cement, this.wire)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(606000))
			.andExpect(jsonPath("$.lines.length()").value(2))
			.andExpect(jsonPath("$.lines[1].lineNumber").value(2))
			.andExpect(jsonPath("$.lotReference").isEmpty())
			.andExpect(jsonPath("$.number").value(matchesPattern("DEV-\\d{4}-\\d{6}")));
		assertThat(this.jdbc.queryForObject("select count(*) from quote_line where quote_id = ?::uuid", Integer.class, id))
			.isEqualTo(2);

		action(id, "issue").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ISSUED"));
		this.mvc.perform(put("/api/quotes/{id}", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content(example())).andExpect(status().isOk());

		action(id, "accept").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
		this.mvc.perform(put("/api/quotes/{id}", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content(example()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("QUOTE_NOT_EDITABLE"));
	}

	@Test
	void statusChangesFollowTheBusinessRules() throws Exception {
		String id = id(create(example()));
		// Un brouillon n'est ni accepté ni refusé : il doit d'abord être émis.
		action(id, "accept").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_INVALID_TRANSITION"));
		action(id, "issue").andExpect(status().isOk());
		action(id, "refuse").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUSED"));
		action(id, "cancel").andExpect(status().isConflict());

		String other = id(create(example()));
		this.mvc.perform(post("/api/quotes/{id}/cancel", other).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\": \"Doublon\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancellationReason").value("Doublon"));
	}

	@Test
	void anIssuedQuotePastItsValidityIsShownExpiredWithoutBeingChanged() throws Exception {
		String id = id(create("""
				{"customerId": "%s", "quoteDate": "2022-03-23", "validUntil": "2022-04-23",
				 "lines": [{"productId": "%s", "quantity": 1, "unitPrice": 6000}]}""".formatted(this.customer, this.cement)));
		action(id, "issue").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ISSUED"))
			.andExpect(jsonPath("$.displayStatus").value("EXPIRED"));

		this.mvc.perform(get("/api/quotes").with(manager()).param("status", "EXPIRED").param("q", "DEV-"))
			.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(id)).exists());
		this.mvc.perform(get("/api/quotes").with(manager()).param("status", "ISSUED"))
			.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(id)).doesNotExist());
		assertThat(this.jdbc.queryForObject("select status from quote where id = ?::uuid", String.class, id))
			.isEqualTo("ISSUED");
	}

	@Test
	void duplicateCreatesAFreshDraft() throws Exception {
		String id = id(create(example()));
		action(id, "issue");
		JsonNode copy = read(action(id, "duplicate").andExpect(status().isCreated()));
		assertThat(copy.get("id").asString()).isNotEqualTo(id);
		assertThat(copy.get("status").asString()).isEqualTo("DRAFT");
		assertThat(copy.get("total").decimalValue()).isEqualByComparingTo("952000");
		assertThat(copy.get("lotReference").asString()).isEqualTo("2");
	}

	@Test
	void searchFindsQuotesByNumberCustomerOrSubject() throws Exception {
		String id = id(create(example()));
		for (String q : new String[] { "ong help", "latrine", read(this.mvc.perform(get("/api/quotes/{id}", id)
			.with(manager()))).get("number").asString() }) {
			this.mvc.perform(get("/api/quotes").with(manager()).param("q", q))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(id)).exists());
		}
		this.mvc.perform(get("/api/quotes").with(manager()).param("from", "2022-03-24"))
			.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(id)).doesNotExist());
	}

	// --- Droits et dépôts ---------------------------------------------------------------------------

	@Test
	void onlyQuoteManagersWriteQuotes() throws Exception {
		String id = id(create(example()));
		RequestPostProcessor cashier = actor(Permissions.SALE_READ, Permissions.SALE_CREATE, Permissions.PRODUCT_READ,
				Permissions.CUSTOMER_READ);
		this.mvc.perform(get("/api/quotes").with(cashier)).andExpect(status().isForbidden());
		this.mvc.perform(post("/api/quotes").with(cashier).contentType(MediaType.APPLICATION_JSON).content(example()))
			.andExpect(status().isForbidden());
		RequestPostProcessor reader = actor(Permissions.QUOTE_READ);
		this.mvc.perform(get("/api/quotes/{id}", id).with(reader)).andExpect(status().isOk());
		this.mvc.perform(post("/api/quotes/{id}/issue", id).with(reader)).andExpect(status().isForbidden());
	}

	@Test
	void aQuoteOfAnotherWarehouseIsInvisible() throws Exception {
		String inDepot = id(this.mvc.perform(post("/api/quotes").with(manager()).header("X-Warehouse-Id", this.depot.toString())
			.contentType(MediaType.APPLICATION_JSON).content(example())).andExpect(status().isCreated()));

		// Utilisateur sans warehouse:all, rattaché au magasin principal : le devis du dépôt n'existe pas pour lui.
		RequestPostProcessor local = actor(Permissions.QUOTE_READ, Permissions.QUOTE_MANAGE);
		this.mvc.perform(get("/api/quotes/{id}", inDepot).with(local)).andExpect(status().isNotFound());
		this.mvc.perform(post("/api/quotes/{id}/issue", inDepot).with(local)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/quotes").with(local))
			.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(inDepot)).doesNotExist());
		this.mvc.perform(get("/api/quotes").with(local).param("allWarehouses", "true")).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/quotes").with(manager()).param("allWarehouses", "true"))
			.andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(inDepot)).exists());
	}

	// --- Facturation --------------------------------------------------------------------------------

	@Test
	void theInvoiceIsTheSaleRecordedFromTheQuoteLinkedOnlyOnce() throws Exception {
		stockIn(this.cement, 200);
		this.mvc.perform(post("/api/cash-register/open").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"openingBalance\": 0}")).andExpect(status().isCreated());
		String id = id(create(example()));
		// Un brouillon n'est pas facturable.
		String early = saleId(sale(this.customer));
		invoice(id, early).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_INVALID_TRANSITION"));

		action(id, "issue");
		action(id, "accept");
		int movementsBefore = count("stock_movement");

		// Vente d'un autre client : refusée.
		UUID other = this.jdbc.queryForObject(
				"insert into customer (code, name) values ('CLI-DEV-AUT', 'Autre client') returning id", UUID.class);
		invoice(id, saleId(sale(other))).andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("QUOTE_SALE_MISMATCH"));

		String sale = saleId(sale(this.customer));
		int movementsAfterSale = count("stock_movement");
		invoice(id, sale)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("INVOICED"))
			.andExpect(jsonPath("$.saleId").value(sale))
			.andExpect(jsonPath("$.saleNumber").value(matchesPattern("VTE-\\d{4}-\\d{6}")));
		// Le rattachement n'écrit aucun mouvement : seules les ventes en ont produit.
		assertThat(count("stock_movement")).isEqualTo(movementsAfterSale);
		assertThat(movementsAfterSale).isGreaterThan(movementsBefore);

		// Même demande répétée (double clic) : sans effet ; autre vente : refusée.
		invoice(id, sale).andExpect(status().isOk()).andExpect(jsonPath("$.saleId").value(sale));
		invoice(id, saleId(sale(this.customer))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("QUOTE_ALREADY_INVOICED"));

		// Une vente ne facture qu'un devis.
		String second = id(create(example()));
		action(second, "issue");
		invoice(second, sale).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SALE_ALREADY_LINKED_TO_QUOTE"));

		// Informations du devis reprises sur la facture, y compris pour un vendeur sans quote:read.
		this.mvc.perform(get("/api/quotes/for-sale/{saleId}", sale).with(actor(Permissions.SALE_READ)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lotReference").value("2"))
			.andExpect(jsonPath("$.beneficiaries").value(20))
			.andExpect(jsonPath("$.deliveryAddress").isEmpty());
		// Vente sans devis : réponse vide.
		assertThat(this.mvc.perform(get("/api/quotes/for-sale/{saleId}", early).with(actor(Permissions.SALE_READ)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString()).isEmpty();
	}

	// --- Quantité par bénéficiaire -------------------------------------------------------------------

	@Test
	void aQuantityPerBeneficiaryIsMultipliedByTheBeneficiariesByTheServer() throws Exception {
		// 20 bénéficiaires : 6 sacs, 2 barres et 1 rouleau chacun ; quantité totale ignorée si envoyée.
		JsonNode quote = read(create("""
				{"customerId": "%s", "beneficiaries": 20,
				 "lines": [{"productId": "%s", "quantityPerBeneficiary": 6, "quantity": 1, "unitPrice": 6000},
				           {"productId": "%s", "quantityPerBeneficiary": 2, "unitPrice": 5500},
				           {"productId": "%s", "quantity": 20, "unitPrice": 600}]}"""
			.formatted(this.customer, this.cement, this.iron, this.wire)).andExpect(status().isCreated()));

		JsonNode lines = quote.get("lines");
		assertThat(lines.get(0).get("quantityPerBeneficiary").asInt()).isEqualTo(6);
		assertThat(lines.get(0).get("quantity").asInt()).isEqualTo(120);
		assertThat(lines.get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("720000");
		assertThat(lines.get(1).get("quantity").asInt()).isEqualTo(40);
		// Ligne sans quantité par bénéficiaire : quantité saisie, telle quelle.
		assertThat(lines.get(2).get("quantityPerBeneficiary").isNull()).isTrue();
		assertThat(lines.get(2).get("quantity").asInt()).isEqualTo(20);
		assertThat(quote.get("total").decimalValue()).isEqualByComparingTo("952000");

		// Moins de bénéficiaires : quantités et total recalculés à l'enregistrement.
		String id = quote.get("id").asString();
		this.mvc.perform(put("/api/quotes/{id}", id).with(manager()).contentType(MediaType.APPLICATION_JSON).content("""
				{"customerId": "%s", "beneficiaries": 10,
				 "lines": [{"productId": "%s", "quantityPerBeneficiary": 6, "unitPrice": 6000}]}"""
			.formatted(this.customer, this.cement)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[0].quantity").value(60))
			.andExpect(jsonPath("$.total").value(360000));
	}

	@Test
	void aQuantityPerBeneficiaryNeedsTheNumberOfBeneficiaries() throws Exception {
		create("""
				{"customerId": "%s", "lines": [{"productId": "%s", "quantityPerBeneficiary": 6, "unitPrice": 6000}]}"""
			.formatted(this.customer, this.cement))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("QUOTE_BENEFICIARIES_REQUIRED"));
		// Ni quantité ni quantité par bénéficiaire.
		create("""
				{"customerId": "%s", "beneficiaries": 5, "lines": [{"productId": "%s", "unitPrice": 6000}]}"""
			.formatted(this.customer, this.cement)).andExpect(status().isBadRequest());
		// Quantité totale au-delà du plafond d'une ligne.
		create("""
				{"customerId": "%s", "beneficiaries": 1000,
				 "lines": [{"productId": "%s", "quantityPerBeneficiary": 101, "unitPrice": 1}]}"""
			.formatted(this.customer, this.cement)).andExpect(status().isBadRequest());
		assertThat(count("quote")).isZero();
	}

	@Test
	void theTotalQuantityIsWhatTheInvoicingSaleTakesOutOfStock() throws Exception {
		stockIn(this.cement, 500);
		this.mvc.perform(post("/api/cash-register/open").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"openingBalance\": 0}")).andExpect(status().isCreated());
		JsonNode quote = read(create("""
				{"customerId": "%s", "beneficiaries": 20,
				 "lines": [{"productId": "%s", "quantityPerBeneficiary": 6, "unitPrice": 6000}]}"""
			.formatted(this.customer, this.cement)));
		String id = quote.get("id").asString();
		int totalQuantity = quote.get("lines").get(0).get("quantity").asInt();
		action(id, "issue");
		int before = stockLevel(this.cement);
		// Le devis seul ne touche pas au stock.
		assertThat(before).isEqualTo(500);

		// Vente préparée à la caisse avec la quantité totale du devis.
		String sale = read(this.mvc.perform(post("/api/sales").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"customerId": "%s", "paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": %d}]}"""
				.formatted(this.customer, this.cement, totalQuantity)))
			.andExpect(status().isCreated())).get("id").asString();
		invoice(id, sale).andExpect(status().isOk());

		assertThat(stockLevel(this.cement)).isEqualTo(before - 120);
	}

	// --- Outils -------------------------------------------------------------------------------------

	private String example() {
		return """
				{"customerId": "%s", "quoteDate": "2022-03-23",
				 "subject": "Fourniture des matériels de latrine au profit des PDI de la commune de Pissila",
				 "lotReference": "2", "beneficiaries": 20,
				 "lines": [{"productId": "%s", "quantity": 120, "unitPrice": 6000},
				           {"productId": "%s", "quantity": 40, "unitPrice": 5500},
				           {"productId": "%s", "quantity": 20, "unitPrice": 600}]}"""
			.formatted(this.customer, this.cement, this.iron, this.wire);
	}

	private String line(UUID productId, int quantity, String unitPrice) {
		return "{\"customerId\": \"%s\", \"lines\": [{\"productId\": \"%s\", \"quantity\": %d, \"unitPrice\": %s}]}"
			.formatted(this.customer, productId, quantity, unitPrice);
	}

	private ResultActions create(String body) throws Exception {
		return this.mvc.perform(post("/api/quotes").with(manager()).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions action(String id, String action) throws Exception {
		return this.mvc.perform(post("/api/quotes/{id}/" + action, id).with(manager()));
	}

	private ResultActions invoice(String id, String saleId) throws Exception {
		return this.mvc.perform(post("/api/quotes/{id}/invoice", id).with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"saleId\": \"%s\"}".formatted(saleId)));
	}

	private ResultActions sale(UUID customerId) throws Exception {
		return this.mvc.perform(post("/api/sales").with(manager()).contentType(MediaType.APPLICATION_JSON).content("""
				{"customerId": "%s", "paymentMethod": "CASH", "items": [{"productId": "%s", "quantity": 1}]}"""
			.formatted(customerId, this.cement))).andExpect(status().isCreated());
	}

	private String saleId(ResultActions sale) throws Exception {
		return read(sale).get("id").asString();
	}

	private int stockLevel(UUID productId) {
		return this.jdbc.queryForList("select quantity from stock_level where warehouse_id = ? and product_id = ?",
				Integer.class, this.main, productId).stream().findFirst().orElse(0);
	}

	private void stockIn(UUID productId, int quantity) throws Exception {
		this.mvc.perform(post("/api/stock/movements").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"quantity\": %d, \"reason\": \"SUPPLY\"}".formatted(productId, quantity)))
			.andExpect(status().isCreated());
	}

	private UUID product(String sku, String name, int purchasePrice, int retailPrice) {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values (?, ?, (select id from category limit 1), ?, ?, ?) returning id""", UUID.class, sku, name,
				purchasePrice, retailPrice, retailPrice);
	}

	private int count(String table) {
		return this.jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	private String id(ResultActions result) throws Exception {
		return read(result).get("id").asString();
	}

	private JsonNode read(ResultActions result) throws Exception {
		return this.json.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private RequestPostProcessor manager() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.manager, permissions));
	}

}
