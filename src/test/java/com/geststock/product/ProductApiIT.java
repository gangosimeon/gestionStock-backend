package com.geststock.product;

import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Produits de bout en bout sur PostgreSQL : contrat front, règles, absence de stock. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
@WithPermissions(all = true)
class ProductApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	private String frames;

	private String lenses;

	@BeforeEach
	void setUp() {
		this.frames = this.jdbc.queryForObject("select id::text from category where name = 'Montures'", String.class);
		this.lenses = this.jdbc.queryForObject("select id::text from category where name = 'Lentilles de contact'",
				String.class);
	}

	@Test
	void acceptsTheCurrentFrontPayloadButNeverStoresStock() throws Exception {
		// Charge utile exacte du drawer produit du front : stockQuantity et categoryName inclus.
		String id = idOf(create("""
				{"sku": "MON-RB3025", "name": "Monture Ray-Ban RB3025", "categoryId": "%s",
				 "categoryName": "Montures", "purchasePrice": 15000, "retailPrice": 35000,
				 "wholesalePrice": 28000, "stockQuantity": 12, "alertThreshold": 3}
				""".formatted(this.frames))
			.andExpect(jsonPath("$.categoryName").value("Montures"))
			.andExpect(jsonPath("$.unit").value("UNIT"))
			.andExpect(jsonPath("$.retailPrice").value(35000))
			.andExpect(jsonPath("$.stockQuantity").value(0))
			.andExpect(jsonPath("$.active").value(true)));

		this.mvc.perform(put("/api/products/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
				{"sku": "MON-RB3025", "name": "Monture Ray-Ban RB3025 noire", "categoryId": "%s",
				 "purchasePrice": 16000, "retailPrice": 36000, "wholesalePrice": 29000,
				 "stockQuantity": 999, "alertThreshold": 4}
				""".formatted(this.frames)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Monture Ray-Ban RB3025 noire"))
			.andExpect(jsonPath("$.stockQuantity").value(0));

		List<String> stockColumns = this.jdbc.queryForList("""
				select column_name from information_schema.columns
				where table_name = 'product' and (column_name like '%stock%' and column_name <> 'max_stock_level'
				   or column_name like '%quantity%')""", String.class);
		assertThat(stockColumns).as("aucune colonne de quantité dans product").isEmpty();
	}

	@Test
	void supportsTheOpticalCatalogue() throws Exception {
		String brand = idOf(this.mvc.perform(post("/api/brands").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Acuvue Test\"}")));

		create("""
				{"sku": "LEN-OAS-6", "barcode": "0733905561234", "name": "Lentilles Acuvue Oasys (boîte de 6)",
				 "description": "Lentilles bimensuelles", "categoryId": "%s", "brandId": "%s", "unit": "BOX",
				 "purchasePrice": 9000, "retailPrice": 18000, "wholesalePrice": 15000,
				 "alertThreshold": 5, "maxStockLevel": 60}
				""".formatted(this.lenses, brand))
			.andExpect(jsonPath("$.unit").value("BOX"))
			.andExpect(jsonPath("$.brandName").value("Acuvue Test"))
			.andExpect(jsonPath("$.barcode").value("0733905561234"))
			.andExpect(jsonPath("$.maxStockLevel").value(60));
	}

	@Test
	void enforcesUniqueSkuAndBarcode() throws Exception {
		create(product("REF-001", "4006381333931")).andExpect(status().isCreated());

		create(product("ref-001", null))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SKU_ALREADY_USED"));
		create(product("REF-002", "4006381333931"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BARCODE_ALREADY_USED"));
	}

	@Test
	void validatesServerSide() throws Exception {
		create("""
				{"sku": "bad sku", "barcode": "12AB", "name": "", "categoryId": null,
				 "purchasePrice": -1, "retailPrice": 10, "wholesalePrice": 10, "alertThreshold": 5, "maxStockLevel": 2}
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field", hasItem("sku")))
			.andExpect(jsonPath("$.errors[*].field", hasItem("barcode")))
			.andExpect(jsonPath("$.errors[*].field", hasItem("name")))
			.andExpect(jsonPath("$.errors[*].field", hasItem("categoryId")))
			.andExpect(jsonPath("$.errors[*].field", hasItem("purchasePrice")))
			.andExpect(jsonPath("$.errors[*].field", hasItem("maxStockLevelConsistent")));

		create(product("REF-404", null).replace(this.frames, UUID.randomUUID().toString()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
	}

	@Test
	void inactiveCategoryCannotBeChosenButIsKeptByExistingProducts() throws Exception {
		String id = idOf(create(product("REF-CAT", null)));
		this.mvc.perform(patch("/api/categories/{id}/active", this.frames).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": false}")).andExpect(status().isOk());

		create(product("REF-CAT-2", null))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CATEGORY_INACTIVE"));
		this.mvc.perform(put("/api/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content(product("REF-CAT", null).replace("\"Produit test\"", "\"Produit renommé\"")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categoryName").value("Montures"));
	}

	@Test
	void searchesFiltersPaginatesAndSorts() throws Exception {
		create(product("ZZ-AAA", "1111111111111").replace("Produit test", "Monture Alpha"));
		create(product("ZZ-BBB", null).replace("Produit test", "Monture Beta").replace("35000", "50000"));
		create(product("ZZ-CCC", null).replace(this.frames, this.lenses).replace("Produit test", "Lentille Gamma"));

		this.mvc.perform(get("/api/products").param("q", "zz-").param("categoryId", this.frames))
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/products").param("q", "1111111111111"))
			.andExpect(jsonPath("$.items[0].sku").value("ZZ-AAA"));
		this.mvc.perform(get("/api/products").param("q", "zz-").param("sort", "retailPrice,desc").param("page", "0")
			.param("size", "2"))
			.andExpect(jsonPath("$.items[0].sku").value("ZZ-BBB"))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.totalPages").value(2));
		this.mvc.perform(get("/api/products").param("sort", "stockQuantity,asc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_SORT_FIELD"));
	}

	@Test
	void deleteDeactivatesAndCanBeUndone() throws Exception {
		String id = idOf(create(product("REF-DEL", null)));

		this.mvc.perform(delete("/api/products/{id}", id)).andExpect(jsonPath("$.ok").value(true));
		assertThat(this.jdbc.queryForObject("select active from product where id = ?::uuid", Boolean.class, id))
			.as("ligne conservée").isFalse();
		this.mvc.perform(get("/api/products").param("q", "REF-DEL")).andExpect(jsonPath("$.total").value(0));
		this.mvc.perform(get("/api/products").param("q", "REF-DEL").param("status", "INACTIVE"))
			.andExpect(jsonPath("$.total").value(1));

		this.mvc.perform(patch("/api/products/{id}/active", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": true}"))
			.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	@WithPermissions({ "product:read", "product:create" })
	void permissionsAreCheckedPerAction() throws Exception {
		String id = idOf(create(product("REF-PERM", null)));

		this.mvc.perform(put("/api/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content(product("REF-PERM", null)))
			.andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/products/{id}", id)).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/products").param("q", "REF-PERM")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].sku", not(hasItem("AUTRE"))));
	}

	private String product(String sku, String barcode) {
		return """
				{"sku": "%s", %s"name": "Produit test", "categoryId": "%s",
				 "purchasePrice": 15000, "retailPrice": 35000, "wholesalePrice": 28000, "alertThreshold": 3}
				""".formatted(sku, barcode != null ? "\"barcode\": \"" + barcode + "\", " : "", this.frames);
	}

	private ResultActions create(String json) throws Exception {
		return this.mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String idOf(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		int start = body.indexOf("\"id\":\"") + 6;
		return body.substring(start, body.indexOf('"', start));
	}

}
