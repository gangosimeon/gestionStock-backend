package com.geststock.product;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photo facultative d'un produit : envoi d'une photo de téléphone, conservée réduite en JPEG, version
 * exposée dans le produit (cache du navigateur), remplacement, retrait, droits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
@WithPermissions(all = true)
class ProductImageApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private EntityManager entityManager;

	private String productId;

	@BeforeEach
	void setUp() {
		// Produit créé en SQL : indépendant des droits testés (lecture seule dans certains cas).
		this.productId = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price, alert_threshold)
				select 'IMG-001', 'Ciment CPJ-45', id, 5000, 7300, 7000, 3 from category where name = 'Montures'
				returning id::text""", String.class);
	}

	/** Écritures JPA envoyées à la base avant une lecture JDBC (test transactionnel). */
	private void flush() {
		this.entityManager.flush();
	}

	@Test
	void aPhoneSizedPhotoIsStoredReducedAndItsVersionIsExposedWithTheProduct() throws Exception {
		this.mvc.perform(get("/api/products/{id}", this.productId)).andExpect(jsonPath("$.imageVersion").value(nullValue()));
		this.mvc.perform(get("/api/products/{id}/image", this.productId))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_NOT_FOUND"));

		upload(photo(3000, 2000, "jpeg"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contentType").value("image/jpeg"))
			.andExpect(jsonPath("$.width").value(512))
			.andExpect(jsonPath("$.height").value(341))
			.andExpect(jsonPath("$.version").value(matchesPattern("[0-9a-f]{64}")));

		flush();
		String version = this.jdbc.queryForObject("select sha256 from product_image where product_id = ?::uuid",
				String.class, this.productId);
		Integer stored = this.jdbc.queryForObject("select size_bytes from product_image where product_id = ?::uuid",
				Integer.class, this.productId);
		assertThat(stored).isLessThan(60 * 1024);

		// Version dans la fiche et dans la liste (sans lire l'image).
		this.mvc.perform(get("/api/products/{id}", this.productId)).andExpect(jsonPath("$.imageVersion").value(version));
		this.mvc.perform(get("/api/products").param("q", "IMG-001"))
			.andExpect(jsonPath("$.items[0].imageVersion").value(version));

		byte[] served = this.mvc.perform(get("/api/products/{id}/image", this.productId))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/jpeg"))
			.andExpect(header().string("ETag", "\"" + version + "\""))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(ImageIO.read(new ByteArrayInputStream(served)).getWidth()).isEqualTo(512);
	}

	@Test
	void replacingThePhotoChangesItsVersionWithoutTouchingTheProduct() throws Exception {
		Long productVersion = this.jdbc.queryForObject("select version from product where id = ?::uuid", Long.class,
				this.productId);
		upload(photo(800, 800, "png")).andExpect(status().isOk());
		flush();
		String first = this.jdbc.queryForObject("select sha256 from product_image where product_id = ?::uuid",
				String.class, this.productId);

		upload(photo(600, 300, "jpeg")).andExpect(status().isOk()).andExpect(jsonPath("$.width").value(512));
		flush();

		assertThat(this.jdbc.queryForObject("select count(*) from product_image where product_id = ?::uuid",
				Integer.class, this.productId)).isEqualTo(1);
		assertThat(this.jdbc.queryForObject("select sha256 from product_image where product_id = ?::uuid",
				String.class, this.productId)).isNotEqualTo(first);
		// La fiche produit n'est pas modifiée : aucun conflit avec une modification en cours.
		assertThat(this.jdbc.queryForObject("select version from product where id = ?::uuid", Long.class,
				this.productId)).isEqualTo(productVersion);
	}

	@Test
	void thePhotoCanBeRemoved() throws Exception {
		upload(photo(200, 200, "png")).andExpect(status().isOk());

		this.mvc.perform(delete("/api/products/{id}/image", this.productId)).andExpect(status().isOk());

		this.mvc.perform(get("/api/products/{id}/image", this.productId)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/products/{id}", this.productId)).andExpect(jsonPath("$.imageVersion").value(nullValue()));
		// Retirer une photo absente : sans effet.
		this.mvc.perform(delete("/api/products/{id}/image", this.productId)).andExpect(status().isOk());
	}

	@Test
	void filesThatAreNotPhotosAndUnknownProductsAreRefused() throws Exception {
		upload(new MockMultipartFile("file", "photo.jpg", "image/jpeg", "<script>alert(1)</script>".getBytes()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PRODUCT_IMAGE"));
		this.mvc.perform(multipart(HttpMethod.PUT, "/api/products/{id}/image", this.productId))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PRODUCT_IMAGE"));
		this.mvc.perform(multipart(HttpMethod.PUT, "/api/products/{id}/image", "00000000-0000-0000-0000-000000000000")
			.file(photo(50, 50, "png")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
		flush();
		assertThat(this.jdbc.queryForObject("select count(*) from product_image where product_id = ?::uuid",
				Integer.class, this.productId)).isZero();
	}

	@Test
	@WithPermissions("product:read")
	void readingIsAllowedButChangingThePhotoRequiresProductUpdate() throws Exception {
		upload(photo(100, 100, "png")).andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/products/{id}/image", this.productId)).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/products/{id}/image", this.productId)).andExpect(status().isNotFound());
	}

	private ResultActions upload(MockMultipartFile file) throws Exception {
		return this.mvc.perform(multipart(HttpMethod.PUT, "/api/products/{id}/image", this.productId).file(file));
	}

	private static MockMultipartFile photo(int width, int height, String format) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(width % 255, height % 255, 120));
		g.fillRect(0, 0, width, height);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return new MockMultipartFile("file", "photo." + format, "image/" + format, out.toByteArray());
	}

}
