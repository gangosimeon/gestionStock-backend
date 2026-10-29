package com.geststock.product;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

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
import org.springframework.transaction.annotation.Transactional;

import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Non-régression du stockage des photos (lot 1, étape 2+4b) : une image source fixe, envoyée par la route
 * de l'application puis relue par la même route que le navigateur, donne exactement les octets produits par
 * le traitement de l'image dans la même exécution ; taille et empreinte enregistrées concordent avec ces
 * octets ; l'image servie est un JPEG aux dimensions attendues. Aucune valeur figée : l'encodeur JPEG dépend
 * du JDK.
 *
 * <p>Historique : mesure de référence du 2026-10-29, sur le commit 8294b64 (avant toute modification du
 * service des images), pour cette image source : 24 996 octets, SHA-256
 * 71528b3deeb1c8a377c660c3f0139bc50eb8f55d11eabaeed8257169c34a0d3b ; identique après l'étape 2+4b.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
@WithPermissions(all = true)
class ProductImageStorageIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private EntityManager entityManager;

	private String productId;

	@BeforeEach
	void setUp() {
		this.productId = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price, alert_threshold)
				select 'IMG-REF-001', 'Photo de référence', id, 5000, 7300, 7000, 3 from category where name = 'Montures'
				returning id::text""", String.class);
	}

	@Test
	void aStoredPhotoIsReadBackByteForByteAsProcessed() throws Exception {
		byte[] source = referenceSource();
		ProductImageProcessor.Processed expected = ProductImageProcessor.process(source);

		this.mvc.perform(multipart(HttpMethod.PUT, "/api/products/{id}/image", this.productId)
			.file(new MockMultipartFile("file", "reference.png", "image/png", source)))
			.andExpect(status().isOk());
		this.entityManager.flush();

		byte[] served = this.mvc.perform(get("/api/products/{id}/image", this.productId))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/jpeg"))
			.andReturn().getResponse().getContentAsByteArray();

		assertThat(served).as("octets relus = sortie du traitement, même exécution").isEqualTo(expected.data());

		// Format et dimensions : JPEG, 800 × 600 réduit à 512 px de côté au plus, proportions gardées.
		assertThat(served).startsWith((byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(served));
		assertThat(decoded.getWidth()).isEqualTo(512).isEqualTo(expected.width());
		assertThat(decoded.getHeight()).isEqualTo(384).isEqualTo(expected.height());

		Integer storedSize = this.jdbc.queryForObject("select size_bytes from product_image where product_id = ?::uuid",
				Integer.class, this.productId);
		String storedSha = this.jdbc.queryForObject("select sha256 from product_image where product_id = ?::uuid",
				String.class, this.productId);
		Integer storedWidth = this.jdbc.queryForObject("select width from product_image where product_id = ?::uuid",
				Integer.class, this.productId);
		Integer storedHeight = this.jdbc.queryForObject("select height from product_image where product_id = ?::uuid",
				Integer.class, this.productId);
		assertThat(storedSize).as("size_bytes = octets servis").isEqualTo(served.length);
		assertThat(storedSha).as("sha256 = empreinte des octets servis").isEqualTo(sha256(served))
			.isEqualTo(expected.sha256());
		assertThat(storedWidth).isEqualTo(512);
		assertThat(storedHeight).isEqualTo(384);
	}

	/** Image source déterministe (dégradé et motif), sans dépendance au hasard ni à un fichier. */
	private static byte[] referenceSource() throws IOException {
		BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < 600; y++) {
			for (int x = 0; x < 800; x++) {
				int r = x * 255 / 799;
				int g = y * 255 / 599;
				int b = ((x / 40 + y / 40) % 2) * 160;
				image.setRGB(x, y, (r << 16) | (g << 8) | b);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private static String sha256(byte[] data) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
	}

}
