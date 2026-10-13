package com.geststock.category;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Catégories et marques, de bout en bout sur PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class CatalogReferenceApiIT {

	@Autowired
	private MockMvc mvc;

	@Test
	@WithPermissions(all = true)
	void opticalCategoriesAreProvidedWithTheFrontContract() throws Exception {
		this.mvc.perform(get("/api/categories"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.items[*].name", hasItem("Montures")))
			.andExpect(jsonPath("$.items[*].name", hasItem("Lentilles de contact")))
			.andExpect(jsonPath("$.items[0].id").isString());
	}

	@Test
	@WithPermissions(all = true)
	void categoryLifecycleWithCaseInsensitiveUniqueness() throws Exception {
		String id = idOf(create("/api/categories", "{\"name\": \"Lunettes de soleil\", \"description\": \"Solaires\"}"));
		// La recherche couvre aussi la description.
		this.mvc.perform(get("/api/categories").param("q", "solaires"))
			.andExpect(jsonPath("$.items[*].name", hasItem("Lunettes de soleil")));
		this.mvc.perform(get("/api/categories").param("q", "introuvable")).andExpect(jsonPath("$.total").value(0));

		create("/api/categories", "{\"name\": \"LUNETTES DE SOLEIL\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CATEGORY_NAME_ALREADY_USED"));

		this.mvc.perform(put("/api/categories/{id}", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Solaires\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Solaires"))
			.andExpect(jsonPath("$.description").doesNotExist());

		this.mvc.perform(delete("/api/categories/{id}", id)).andExpect(jsonPath("$.ok").value(true));
		this.mvc.perform(get("/api/categories")).andExpect(jsonPath("$.items[*].name", not(hasItem("Solaires"))));
		this.mvc.perform(get("/api/categories").param("status", "ALL"))
			.andExpect(jsonPath("$.items[*].name", hasItem("Solaires")));

		this.mvc.perform(patch("/api/categories/{id}/active", id).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": true}"))
			.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	@WithPermissions(all = true)
	void brandLifecycleAndSearch() throws Exception {
		String id = idOf(create("/api/brands", "{\"name\": \"Essilor Test\"}"));
		create("/api/brands", "{\"name\": \"essilor test\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BRAND_NAME_ALREADY_USED"));

		this.mvc.perform(get("/api/brands").param("q", "ESSILOR T"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(id));

		this.mvc.perform(get("/api/brands/{id}", java.util.UUID.randomUUID()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BRAND_NOT_FOUND"));
	}

	@Test
	@WithPermissions(all = true)
	void aDeactivatedBrandStaysOnItsProductsButCannotBeAssignedAnymore() throws Exception {
		String brand = idOf(create("/api/brands", "{\"name\": \"Silhouette Test\"}"));
		this.mvc.perform(put("/api/brands/{id}", brand).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"Silhouette Titan\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Silhouette Titan"));
		String frames = idOf(this.mvc.perform(get("/api/categories").param("q", "Montures"))
			.andExpect(status().isOk()), "items");
		String product = idOf(create("/api/products", productBody("BRD-1", frames, brand)));

		this.mvc.perform(patch("/api/brands/{id}/active", brand).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": false}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));
		// Hors des listes par défaut, visible avec status=ALL.
		this.mvc.perform(get("/api/brands").param("q", "Silhouette"))
			.andExpect(jsonPath("$.items[*].id", not(hasItem(brand))));
		this.mvc.perform(get("/api/brands").param("q", "Silhouette").param("status", "ALL"))
			.andExpect(jsonPath("$.items[*].id", hasItem(brand)));

		// Plus attribuable à un nouveau produit…
		create("/api/products", productBody("BRD-2", frames, brand))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("BRAND_INACTIVE"));
		// … mais le produit qui la porte déjà reste modifiable sans en changer.
		this.mvc.perform(put("/api/products/{id}", product).contentType(MediaType.APPLICATION_JSON)
			.content(productBody("BRD-1", frames, brand).replace("Produit BRD-1", "Produit BRD-1 modifié")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Produit BRD-1 modifié"));

		this.mvc.perform(patch("/api/brands/{id}/active", brand).contentType(MediaType.APPLICATION_JSON)
			.content("{\"active\": true}"))
			.andExpect(status().isOk());
		create("/api/products", productBody("BRD-2", frames, brand)).andExpect(status().isCreated());
	}

	private static String productBody(String sku, String categoryId, String brandId) {
		return """
				{"sku": "%s", "name": "Produit %s", "categoryId": "%s", "brandId": "%s", "purchasePrice": 1000,
				 "retailPrice": 2000, "wholesalePrice": 1800, "alertThreshold": 1}""".formatted(sku, sku, categoryId, brandId);
	}

	private static String idOf(ResultActions result, String listField) throws Exception {
		String body = result.andReturn().getResponse().getContentAsString();
		int start = body.indexOf("\"" + listField + "\":[{\"id\":\"") + listField.length() + 11;
		return body.substring(start, body.indexOf('"', start));
	}

	@Test
	@WithPermissions(all = true)
	void validatesInput() throws Exception {
		create("/api/categories", "{\"name\": \"  \"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		create("/api/brands", "{\"name\": \"" + "x".repeat(101) + "\"}").andExpect(status().isBadRequest());
	}

	@Test
	@WithPermissions({ "category:read", "brand:read" })
	void readersCannotModify() throws Exception {
		this.mvc.perform(get("/api/categories")).andExpect(status().isOk());
		create("/api/categories", "{\"name\": \"Interdit\"}").andExpect(status().isForbidden());
		create("/api/brands", "{\"name\": \"Interdit\"}").andExpect(status().isForbidden());
	}

	private ResultActions create(String path, String json) throws Exception {
		return this.mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String idOf(ResultActions result) throws Exception {
		String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		int start = body.indexOf("\"id\":\"") + 6;
		return body.substring(start, body.indexOf('"', start));
	}

}
