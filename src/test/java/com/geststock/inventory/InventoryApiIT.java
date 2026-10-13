package com.geststock.inventory;

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

import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inventaires de bout en bout sur PostgreSQL : workflow, quantité théorique serveur, écarts,
 * validation (permission, mouvements de stock), inventaire terminé figé, contrat front.
 * Contraintes différées forcées avant la fin de chaque test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class InventoryApiIT {

	private static final String[] COUNTER = { Permissions.INVENTORY_READ, Permissions.INVENTORY_CREATE,
			Permissions.WAREHOUSE_READ, Permissions.STOCK_READ };

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID user;

	private UUID main;

	private UUID accessories;

	private UUID caseProduct;

	private UUID cloth;

	private UUID frame;

	@BeforeEach
	void setUp() throws Exception {
		this.user = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('inventory_api_user', 'Inventoriste') returning id",
				UUID.class);
		this.main = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		this.accessories = this.jdbc.queryForObject("select id from category where name = 'Accessoires'", UUID.class);
		this.caseProduct = product("INV-ETU-1", "Étui inventaire", "Accessoires");
		this.cloth = product("INV-CHI-1", "Chiffon inventaire", "Accessoires");
		this.frame = product("INV-MON-1", "Monture inventaire", "Montures");
		stock(this.caseProduct, 10, "SUPPLY");
		stock(this.cloth, 4, "SUPPLY");
		stock(this.frame, 3, "SUPPLY");
	}

	@Test
	void theWholeWorkflowAdjustsTheStockOnValidation() throws Exception {
		String id = idOf(create(manager(), "{\"scope\": \"CATEGORY\", \"categoryId\": \"%s\", \"note\": \"Rayon accessoires\"}"
			.formatted(this.accessories))
			.andExpect(jsonPath("$.number").value(matchesPattern("INV-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.status").value("DRAFT"))
			.andExpect(jsonPath("$.warehouseId").value(this.main.toString()))
			.andExpect(jsonPath("$.itemsCount").value(2))
			.andExpect(jsonPath("$.lines[*].productSku", hasItem("INV-ETU-1"))));

		counts(counter(), id, this.caseProduct, 8)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVENTORY_INVALID_TRANSITION"));
		action(counter(), id, "start").andExpect(jsonPath("$.status").value("IN_PROGRESS"));

		// Quantité théorique relevée par le serveur au moment du comptage.
		counts(counter(), id, this.caseProduct, 8)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COUNTING"))
			.andExpect(jsonPath("$.countedCount").value(1));
		action(counter(), id, "submit")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVENTORY_LINES_NOT_COUNTED"));
		counts(counter(), id, this.cloth, 5);
		action(counter(), id, "submit").andExpect(jsonPath("$.status").value("VALIDATION"));
		counts(counter(), id, this.cloth, 6).andExpect(status().isConflict());

		this.mvc.perform(get("/api/inventories").with(manager()).param("q", "inv-"))
			.andExpect(jsonPath("$.items[*].id", org.hamcrest.Matchers.hasItem(id)));
		this.mvc.perform(get("/api/inventories").with(manager()).param("q", "introuvable"))
			.andExpect(jsonPath("$.total").value(0));
		this.mvc.perform(get("/api/inventories/{id}", id).with(counter()))
			.andExpect(jsonPath("$.lines[?(@.productSku == 'INV-ETU-1')].systemQuantity").value(10))
			.andExpect(jsonPath("$.lines[?(@.productSku == 'INV-ETU-1')].physicalQuantity").value(8))
			.andExpect(jsonPath("$.lines[?(@.productSku == 'INV-ETU-1')].difference").value(-2))
			.andExpect(jsonPath("$.lines[?(@.productSku == 'INV-CHI-1')].difference").value(1))
			.andExpect(jsonPath("$.totalDifference").value(-1));
		assertThat(level(this.caseProduct)).as("rien n'est appliqué avant validation").isEqualTo(10);

		// La validation exige inventory:validate.
		action(counter(), id, "validate").andExpect(status().isForbidden());
		action(manager(), id, "validate", "{\"comment\": \"Écarts expliqués\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.validatedByUserId").value(this.user.toString()))
			.andExpect(jsonPath("$.validationComment").value("Écarts expliqués"))
			.andExpect(jsonPath("$.lines[*].stockMovementId").isArray());

		assertThat(level(this.caseProduct)).isEqualTo(8);
		assertThat(level(this.cloth)).isEqualTo(5);
		assertThat(level(this.frame)).as("hors périmètre").isEqualTo(3);
		assertThat(this.jdbc.queryForList("""
				select quantity from stock_movement
				where type = 'INVENTORY' and source_type = 'INVENTORY_SESSION' and source_id = ?::uuid order by quantity""",
				Integer.class, id)).containsExactly(-2, 1);

		// Terminé : plus rien ne bouge.
		counts(manager(), id, this.cloth, 1).andExpect(status().isConflict());
		action(manager(), id, "cancel").andExpect(status().isConflict());
		action(manager(), id, "reopen").andExpect(status().isConflict());
		action(manager(), id, "validate").andExpect(status().isConflict());
		assertThat(this.jdbc.queryForList("""
				select distinct action from audit_log
				where entity_type = 'INVENTORY_SESSION' and module = 'INVENTORY' and entity_id = ? and status = 'SUCCESS'""",
				String.class, id)).containsExactlyInAnyOrder("CREATE", "START", "COUNT", "SUBMIT", "VALIDATE");
		checkDeferredConstraints();
	}

	@Test
	void movementsAfterTheCountAreKept() throws Exception {
		String id = selection(this.caseProduct);
		action(manager(), id, "start");
		counts(manager(), id, this.caseProduct, 8); // théorique 10 → écart −2
		stock(this.caseProduct, -3, "SALE"); // vente faite après le comptage : 10 → 7
		action(manager(), id, "submit");

		action(manager(), id, "validate").andExpect(status().isOk());

		assertThat(level(this.caseProduct)).as("7 − 2 : la vente après comptage n'est pas effacée").isEqualTo(5);
		checkDeferredConstraints();
	}

	@Test
	void onlyOneOpenInventoryPerWarehouseAndItCanBeSentBackToCounting() throws Exception {
		String id = selection(this.cloth);
		create(manager(), "{\"scope\": \"FULL\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVENTORY_ALREADY_OPEN"));

		action(manager(), id, "start");
		counts(manager(), id, this.cloth, 0);
		action(manager(), id, "submit");
		action(manager(), id, "reopen", "{\"comment\": \"Recompter le rayon\"}")
			.andExpect(jsonPath("$.status").value("COUNTING"));
		counts(manager(), id, this.cloth, 4).andExpect(jsonPath("$.lines[0].difference").value(0));
		action(manager(), id, "submit");
		action(manager(), id, "validate").andExpect(jsonPath("$.status").value("COMPLETED"));
		assertThat(level(this.cloth)).as("aucun écart après recomptage").isEqualTo(4);

		String other = idOf(create(manager(), "{\"scope\": \"FULL\"}"));
		action(manager(), other, "cancel", "{\"comment\": \"Lancé par erreur\"}")
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancellationReason").value("Lancé par erreur"));
		idOf(create(manager(), "{\"scope\": \"FULL\"}"));
		checkDeferredConstraints();
	}

	@Test
	void theFrontOneShotInventoryValidatesOnlyForAValidator() throws Exception {
		// Contrat du front : {note, lines: [{productId, physicalQuantity}]}.
		this.mvc.perform(post("/api/inventory/sessions").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"note": "Inventaire express", "lines": [{"productId": "%s", "physicalQuantity": 7},
					                                         {"productId": "%s", "physicalQuantity": 4}]}
					""".formatted(this.caseProduct, this.cloth)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.note").value("Inventaire express"))
			.andExpect(jsonPath("$.itemsCount").value(2))
			.andExpect(jsonPath("$.totalDifference").value(-3))
			.andExpect(jsonPath("$.createdByUserId").value(this.user.toString()))
			.andExpect(jsonPath("$.lines[0].systemQuantity").value(10))
			.andExpect(jsonPath("$.lines[0].physicalQuantity").value(7))
			.andExpect(jsonPath("$.lines[0].difference").value(-3));
		assertThat(level(this.caseProduct)).isEqualTo(7);

		// Sans inventory:validate : l'inventaire attend un valideur, le stock ne bouge pas.
		this.mvc.perform(post("/api/inventory/sessions").with(counter()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"lines\": [{\"productId\": \"%s\", \"physicalQuantity\": 1}]}".formatted(this.frame)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("VALIDATION"));
		assertThat(level(this.frame)).isEqualTo(3);

		this.mvc.perform(get("/api/inventory/sessions").with(counter()))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].status").value("VALIDATION"));
		checkDeferredConstraints();
	}

	@Test
	void requestsAreValidated() throws Exception {
		create(manager(), "{\"scope\": \"SELECTION\"}").andExpect(status().isBadRequest());
		create(manager(), "{\"scope\": \"CATEGORY\"}").andExpect(status().isBadRequest());
		create(manager(), "{\"scope\": \"FULL\", \"categoryId\": \"%s\"}".formatted(this.accessories))
			.andExpect(status().isBadRequest());
		create(manager(), "{\"scope\": \"SELECTION\", \"productIds\": [\"%s\"]}".formatted(UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
		create(manager(), "{\"scope\": \"SELECTION\", \"productIds\": [\"%s\", \"%s\"]}".formatted(this.frame, this.frame))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVENTORY_DUPLICATE_PRODUCT"));
		UUID solutions = this.jdbc.queryForObject("select id from category where name = 'Solutions d''entretien'",
				UUID.class);
		create(manager(), "{\"scope\": \"CATEGORY\", \"categoryId\": \"%s\"}".formatted(solutions))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVENTORY_EMPTY"));

		String id = selection(this.cloth);
		action(manager(), id, "start");
		counts(manager(), id, this.frame, 1)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVENTORY_PRODUCT_NOT_IN_SCOPE"));
		counts(manager(), id, this.cloth, -1).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/inventories/{id}", UUID.randomUUID()).with(manager()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
		this.mvc.perform(get("/api/inventories").with(actor(Permissions.SALE_READ))).andExpect(status().isForbidden());
	}

	@Test
	void theScopeIsEditableOnlyInDraftAndCountsMustMatchIt() throws Exception {
		String id = selection(this.caseProduct);
		// Brouillon : on ajoute et on retire des produits.
		this.mvc.perform(post("/api/inventories/{id}/products", id).with(counter()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productIds\": [\"%s\"]}".formatted(this.cloth)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.itemsCount").value(2));
		this.mvc.perform(delete("/api/inventories/{id}/products/{productId}", id, this.frame).with(counter()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVENTORY_PRODUCT_NOT_IN_SCOPE"));
		this.mvc.perform(delete("/api/inventories/{id}/products/{productId}", id, this.caseProduct).with(counter()))
			.andExpect(jsonPath("$.itemsCount").value(1));
		this.mvc.perform(delete("/api/inventories/{id}/products/{productId}", id, this.cloth).with(counter()))
			.andExpect(jsonPath("$.itemsCount").value(0));
		// Un inventaire vide ne démarre pas.
		action(counter(), id, "start")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("INVENTORY_EMPTY"));
		this.mvc.perform(post("/api/inventories/{id}/products", id).with(counter()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productIds\": [\"%s\"]}".formatted(this.caseProduct)))
			.andExpect(status().isOk());
		action(counter(), id, "start").andExpect(status().isOk());

		// Démarré : périmètre figé.
		this.mvc.perform(post("/api/inventories/{id}/products", id).with(counter()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productIds\": [\"%s\"]}".formatted(this.cloth)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVENTORY_INVALID_TRANSITION"));
		this.mvc.perform(delete("/api/inventories/{id}/products/{productId}", id, this.caseProduct).with(counter()))
			.andExpect(status().isConflict());

		// Comptages : uniquement des produits du périmètre, chacun une fois par demande.
		counts(counter(), id, this.frame, 3)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVENTORY_PRODUCT_NOT_IN_SCOPE"));
		this.mvc.perform(put("/api/inventories/{id}/counts", id).with(counter()).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"counts": [{"productId": "%s", "countedQuantity": 9}, {"productId": "%s", "countedQuantity": 8}]}"""
				.formatted(this.caseProduct, this.caseProduct)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVENTORY_DUPLICATE_PRODUCT"));
		counts(counter(), id, this.caseProduct, -1).andExpect(status().isBadRequest());

		// Annulé avant validation : aucun effet sur le stock, plus aucune action possible.
		counts(counter(), id, this.caseProduct, 2).andExpect(status().isOk());
		action(counter(), id, "cancel", "{\"comment\": \"Comptage interrompu\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(level(this.caseProduct)).as("le comptage annulé ne touche pas le stock").isEqualTo(10);
		action(counter(), id, "submit").andExpect(status().isConflict());
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where entity_type = 'INVENTORY_SESSION' and entity_id = ? and status = 'SUCCESS'
				  and action in ('ADD_PRODUCTS', 'REMOVE_PRODUCT', 'COUNT', 'CANCEL')""", Integer.class, id))
			.as("modifications du périmètre, comptage et annulation tracés").isEqualTo(6);
		checkDeferredConstraints();
	}

	@Test
	void anInventoryOfAnInaccessibleWarehouseIsInvisible() throws Exception {
		UUID depot = this.jdbc.queryForObject(
				"insert into warehouse (code, name, type) values ('ZZ-INV', 'Dépôt inventaire', 'WAREHOUSE') returning id",
				UUID.class);
		String id = idOf(this.mvc.perform(post("/api/inventories").with(manager()).header("X-Warehouse-Id", depot)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"scope\": \"SELECTION\", \"productIds\": [\"%s\"]}".formatted(this.frame))));

		// Compteur sans warehouse:all, rattaché par défaut au magasin principal.
		this.mvc.perform(get("/api/inventories/{id}", id).with(counter()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("INVENTORY_NOT_FOUND"));
		action(counter(), id, "start").andExpect(status().isNotFound());
		this.mvc.perform(get("/api/inventories/{id}", id).with(manager())).andExpect(status().isOk());
	}

	// --- Outils ------------------------------------------------------------------------------------

	private void checkDeferredConstraints() {
		this.jdbc.execute("set constraints all immediate");
	}

	private UUID product(String sku, String name, String category) {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values (?, ?, (select id from category where name = ?), 1000, 2000, 1500) returning id""", UUID.class, sku,
				name, category);
	}

	private void stock(UUID productId, int quantity, String reason) throws Exception {
		this.mvc.perform(post("/api/stock/movements").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"productId\": \"%s\", \"quantity\": %d, \"reason\": \"%s\"}".formatted(productId, quantity, reason)))
			.andExpect(status().isCreated());
	}

	private String selection(UUID productId) throws Exception {
		return idOf(create(manager(), "{\"scope\": \"SELECTION\", \"productIds\": [\"%s\"]}".formatted(productId)));
	}

	private ResultActions create(RequestPostProcessor actor, String body) throws Exception {
		return this.mvc.perform(post("/api/inventories").with(actor).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions counts(RequestPostProcessor actor, String id, UUID productId, int counted) throws Exception {
		return this.mvc.perform(put("/api/inventories/{id}/counts", id).with(actor).contentType(MediaType.APPLICATION_JSON)
			.content("{\"counts\": [{\"productId\": \"%s\", \"countedQuantity\": %d}]}".formatted(productId, counted)));
	}

	private ResultActions action(RequestPostProcessor actor, String id, String action) throws Exception {
		return this.mvc.perform(post("/api/inventories/{id}/" + action, id).with(actor));
	}

	private ResultActions action(RequestPostProcessor actor, String id, String action, String body) throws Exception {
		return this.mvc.perform(post("/api/inventories/{id}/" + action, id).with(actor)
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private int level(UUID productId) {
		return this.jdbc.queryForList("select quantity from stock_level where warehouse_id = ? and product_id = ?",
				Integer.class, this.main, productId).stream().findFirst().orElse(0);
	}

	private RequestPostProcessor manager() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor counter() {
		return actor(COUNTER);
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.user, permissions));
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

}
