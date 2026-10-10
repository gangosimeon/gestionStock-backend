package com.geststock.supplier;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fournisseurs de bout en bout sur PostgreSQL : contrat front, règles, droits, lien produit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
@WithPermissions(all = true)
class SupplierApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private String frames;

	@BeforeEach
	void setUp() {
		this.frames = this.jdbc.queryForObject("select id::text from category where name = 'Montures'", String.class);
	}

	@Test
	void followsTheFrontContractFromCreationToDeletion() throws Exception {
		// Charge utile exacte du drawer fournisseur du front.
		ResultActions created = create("""
				{"name": "Essilor Distribution CI", "phone": "+225 20 25 00 10",
				 "email": "commandes@essilor.ci", "address": "Zone Ind. Yopougon, Abidjan",
				 "deliveryLeadTimeDays": 5}
				""")
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", startsWith("/api/suppliers/")))
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.code").value(matchesPattern("FOU-\\d{4,}")))
			.andExpect(jsonPath("$.name").value("Essilor Distribution CI"))
			.andExpect(jsonPath("$.phone").value("+225 20 25 00 10"))
			.andExpect(jsonPath("$.email").value("commandes@essilor.ci"))
			.andExpect(jsonPath("$.address").value("Zone Ind. Yopougon, Abidjan"))
			.andExpect(jsonPath("$.deliveryLeadTimeDays").value(5))
			.andExpect(jsonPath("$.paymentTermsDays").value(0))
			.andExpect(jsonPath("$.active").value(true));
		String id = idOf(created);

		this.mvc.perform(get("/api/suppliers"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", hasItem(id)))
			.andExpect(jsonPath("$.total").isNumber());

		// Modification avec le drawer : téléphone vidé (le front n'envoie plus le champ).
		this.mvc.perform(put("/api/suppliers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Essilor CI", "email": "achats@essilor.ci", "address": "Yopougon", "deliveryLeadTimeDays": 6}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Essilor CI"))
			.andExpect(jsonPath("$.phone").doesNotExist())
			.andExpect(jsonPath("$.email").value("achats@essilor.ci"))
			.andExpect(jsonPath("$.deliveryLeadTimeDays").value(6));

		this.mvc.perform(delete("/api/suppliers/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ok").value(true));
		this.mvc.perform(get("/api/suppliers")).andExpect(jsonPath("$.items[*].id", not(hasItem(id))));
		this.mvc.perform(get("/api/suppliers").param("status", "INACTIVE"))
			.andExpect(jsonPath("$.items[*].id", hasItem(id)));
		this.mvc.perform(get("/api/suppliers/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));

		this.mvc.perform(patch("/api/suppliers/{id}/active", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": true}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	void storesTheExtendedProfileAndKeepsItWhenTheFrontUpdates() throws Exception {
		String id = idOf(create("""
				{"code": "fou-luxo", "name": "Luxottica Afrique de l'Ouest", "contactName": "Awa Koné",
				 "taxId": "CI-1234567-A", "deliveryLeadTimeDays": 10, "paymentTermsDays": 45,
				 "note": "Commande minimum 500 000 FCFA"}
				""")
			.andExpect(jsonPath("$.code").value("FOU-LUXO"))
			.andExpect(jsonPath("$.contactName").value("Awa Koné"))
			.andExpect(jsonPath("$.taxId").value("CI-1234567-A"))
			.andExpect(jsonPath("$.paymentTermsDays").value(45)));

		this.mvc.perform(put("/api/suppliers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Luxottica AOW", "deliveryLeadTimeDays": 12}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value("FOU-LUXO"))
			.andExpect(jsonPath("$.contactName").value("Awa Koné"))
			.andExpect(jsonPath("$.taxId").value("CI-1234567-A"))
			.andExpect(jsonPath("$.paymentTermsDays").value(45))
			.andExpect(jsonPath("$.note").value("Commande minimum 500 000 FCFA"));

		this.mvc.perform(put("/api/suppliers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Luxottica AOW", "contactName": "", "note": "", "paymentTermsDays": 0}
				"""))
			.andExpect(jsonPath("$.contactName").doesNotExist())
			.andExpect(jsonPath("$.note").doesNotExist())
			.andExpect(jsonPath("$.paymentTermsDays").value(0))
			.andExpect(jsonPath("$.deliveryLeadTimeDays").value(12));
	}

	@Test
	void searchesAndPaginatesServerSide() throws Exception {
		create(supplier("Zeta Optique Abidjan", "zeta@optique.ci", "+225 01 11 11 11"));
		create(supplier("Zeta Verres", "contact@verres.ci", "+225 02 22 22 22"));
		create(supplier("Zeta Lentilles", "zeta-lentilles@mail.ci", "+225 03 33 33 33"));
		create("{\"code\": \"ZETA-CODE\", \"name\": \"Autre fournisseur\"}");

		this.mvc.perform(get("/api/suppliers").param("q", "zeta"))
			.andExpect(jsonPath("$.total").value(4));
		this.mvc.perform(get("/api/suppliers").param("q", "VERRES.CI"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Zeta Verres"));
		this.mvc.perform(get("/api/suppliers").param("q", "03 33"))
			.andExpect(jsonPath("$.items[0].name").value("Zeta Lentilles"));
		this.mvc.perform(get("/api/suppliers").param("q", "100%"))
			.andExpect(jsonPath("$.total").value(0));

		this.mvc.perform(get("/api/suppliers").param("q", "zeta").param("page", "1").param("size", "3")
			.param("sort", "name,desc"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(3))
			.andExpect(jsonPath("$.totalPages").value(2))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].name").value("Autre fournisseur"));

		this.mvc.perform(get("/api/suppliers").param("sort", "email,asc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_SORT_FIELD"));
	}

	@Test
	void namesAndCodesAreUnique() throws Exception {
		create("{\"code\": \"FOU-COOPER\", \"name\": \"CooperVision Afrique\"}").andExpect(status().isCreated());

		create("{\"name\": \"COOPERVISION AFRIQUE\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SUPPLIER_NAME_ALREADY_USED"))
			.andExpect(jsonPath("$.message").value("Un fournisseur porte déjà ce nom."));
		create("{\"code\": \"fou-cooper\", \"name\": \"Autre\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SUPPLIER_CODE_ALREADY_USED"));
	}

	@Test
	void validatesServerSide() throws Exception {
		create("""
				{"code": "FOU 01", "name": " ", "phone": "appeler le standard", "email": "pas-un-email",
				 "deliveryLeadTimeDays": -1, "paymentTermsDays": 400, "address": "%s"}
				""".formatted("x".repeat(401)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("code", "name", "phone", "email",
					"deliveryLeadTimeDays", "paymentTermsDays", "address")));

		create("{\"name\": \"Sans point\", \"email\": \"achat@localhost\"}").andExpect(status().isBadRequest());

		this.mvc.perform(get("/api/suppliers/{id}", UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));
		this.mvc.perform(put("/api/suppliers/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Inconnu\"}"))
			.andExpect(status().isNotFound());
	}

	@Test
	@WithPermissions("supplier:read")
	void readersCannotModify() throws Exception {
		this.mvc.perform(get("/api/suppliers")).andExpect(status().isOk());
		create("{\"name\": \"Interdit\"}").andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/suppliers/{id}", UUID.randomUUID())).andExpect(status().isForbidden());
	}

	@Test
	@WithPermissions({ "supplier:read", "supplier:create", "supplier:update" })
	void deactivationRequiresTheDeletePermission() throws Exception {
		String id = idOf(create("{\"name\": \"Fournisseur Gestionnaire\"}"));

		this.mvc.perform(delete("/api/suppliers/{id}", id)).andExpect(status().isForbidden());
		this.mvc.perform(patch("/api/suppliers/{id}/active", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": false}"))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithPermissions("product:read")
	void supplierReadIsRequired() throws Exception {
		this.mvc.perform(get("/api/suppliers")).andExpect(status().isForbidden());
	}

	@Test
	void productsReferenceAnActiveSupplier() throws Exception {
		String supplier = idOf(create("{\"name\": \"Transitions Optical\", \"deliveryLeadTimeDays\": 12}"));

		String product = idOf(this.mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
			.content(product("VER-PHC-TR", supplier)))
			.andExpect(jsonPath("$.supplierId").value(supplier)));
		this.mvc.perform(get("/api/products").param("supplierId", supplier))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(product));

		this.mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
			.content(product("VER-INCONNU", UUID.randomUUID().toString())))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("SUPPLIER_NOT_FOUND"));

		this.mvc.perform(delete("/api/suppliers/{id}", supplier)).andExpect(status().isOk());

		this.mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
			.content(product("VER-NOUVEAU", supplier)))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("SUPPLIER_INACTIVE"));
		// Le produit existant conserve son fournisseur désactivé.
		this.mvc.perform(put("/api/products/{id}", product).contentType(MediaType.APPLICATION_JSON)
			.content(product("VER-PHC-TR", supplier)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.supplierId").value(supplier));
	}

	@Test
	void theDatabaseRejectsAnUnknownDefaultSupplier() throws Exception {
		String product = idOf(this.mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
			.content(product("ACC-FK-TEST", null))));

		assertThatThrownBy(() -> this.jdbc.update("update product set default_supplier_id = ?::uuid where id = ?::uuid",
				UUID.randomUUID().toString(), product))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("fk_product_default_supplier");
	}

	@Test
	void theDatabaseRejectsAReferencedSupplierDeletion() throws Exception {
		String supplier = idOf(create("{\"name\": \"Alcon Laboratoires CI\"}"));
		this.mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON)
			.content(product("SOL-FK-TEST", supplier)))
			.andExpect(status().isCreated());

		assertThatThrownBy(() -> this.jdbc.update("delete from supplier where id = ?::uuid", supplier))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("fk_product_default_supplier");
	}

	@Test
	void theDatabaseEnforcesTheSupplierInvariants() {
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into supplier (code, name, delivery_lead_time_days) values ('FOU-DB', 'Délai négatif', -2)"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_supplier_delivery_lead_time");
	}

	@Test
	void theDatabaseStoresCodesInUpperCase() {
		assertThatThrownBy(() -> this.jdbc.update("insert into supplier (code, name) values ('fou-min', 'Minuscules')"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_supplier_code_format");
	}

	private ResultActions create(String json) throws Exception {
		return this.mvc.perform(post("/api/suppliers").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String supplier(String name, String email, String phone) {
		return """
				{"name": "%s", "email": "%s", "phone": "%s", "deliveryLeadTimeDays": 3}
				""".formatted(name, email, phone);
	}

	private String product(String sku, String supplierId) {
		return """
				{"sku": "%s", "name": "Produit %s", "categoryId": "%s", "supplierId": %s,
				 "purchasePrice": 1000, "retailPrice": 2000, "wholesalePrice": 1500, "alertThreshold": 1}
				""".formatted(sku, sku, this.frames, supplierId != null ? "\"" + supplierId + "\"" : "null");
	}

	private static String idOf(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		int start = body.indexOf("\"id\":\"") + 6;
		return body.substring(start, body.indexOf('"', start));
	}

}
