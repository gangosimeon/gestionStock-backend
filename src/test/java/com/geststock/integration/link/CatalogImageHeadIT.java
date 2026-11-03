package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

/**
 * {@code HEAD /catalog/products/{productId}/image} (G19-D8) sur un vrai serveur HTTP : depuis Servlet 6,
 * {@code HttpServlet.doHead} appelle {@code doGet} et c'est le connecteur de Tomcat qui retire le corps d'une réponse
 * {@code HEAD}, ce que MockMvc (sans connecteur) ne peut pas observer. Statut, en-têtes du {@code GET}, corps réellement
 * vide, et consommation d'une unité de la limite {@code catalog-image-per-link} (le 121ᵉ appel est refusé).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CatalogImageHeadIT {

	private static final String IMAGE = "/api/integration/v1/catalog/products/%s/image";

	private static final Set<String> CATALOG = Set.of("catalog:read");

	@Autowired
	private Environment environment;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private IntegrationTokenService tokens;

	@Autowired
	private JdbcTemplate jdbc;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private TestLinks links;

	private TenantRef company;

	private UUID product;

	@BeforeAll
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
	}

	@AfterAll
	void cleanUp() {
		this.links.cleanUp();
		if (this.product != null) {
			TenantContext.runAs(this.company, () -> this.jdbc.update("delete from product where id = ?", this.product));
		}
	}

	@Test
	void aHeadRequestGivesTheHeadersWithoutBodyAndCountsAgainstTheImageLimit() throws Exception {
		TestLinks.Client client = this.links.client();
		Link link = this.links.active(client, this.company.companyId(), "shop-g19-head-" + UUID.randomUUID(), CATALOG);
		String token = this.tokens.issue(link.id(), client.clientId(), CATALOG).value();
		byte[] jpeg = jpeg(Color.DARK_GRAY);
		this.product = productWithImage(jpeg);
		awaitFreshWindow();

		// 119 GET, puis HEAD en 120ᵉ appel : servi, en-têtes du GET, aucun octet de corps sur le fil.
		for (int i = 0; i < 119; i++) {
			assertThat(send("GET", token).statusCode()).as("GET n° %d", i + 1).isEqualTo(200);
		}
		HttpResponse<byte[]> head = send("HEAD", token);
		assertThat(head.statusCode()).isEqualTo(200);
		assertThat(head.headers().firstValue("Content-Type")).hasValue("image/jpeg");
		assertThat(head.headers().firstValue("Content-Length")).hasValue(String.valueOf(jpeg.length));
		assertThat(head.headers().firstValue("ETag")).hasValue("\"" + sha256(jpeg) + "\"");
		assertThat(head.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
		assertThat(head.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
		assertThat(head.body()).as("corps d'une réponse HEAD").isEmpty();

		// Le HEAD a consommé une unité : le 121ᵉ appel, HEAD ou GET, est refusé.
		HttpResponse<byte[]> refusedHead = send("HEAD", token);
		assertThat(refusedHead.statusCode()).isEqualTo(429);
		assertThat(refusedHead.headers().firstValue("Retry-After")).isPresent();
		assertThat(refusedHead.body()).isEmpty();
		HttpResponse<byte[]> refusedGet = send("GET", token);
		assertThat(refusedGet.statusCode()).isEqualTo(429);
		assertThat(refusedGet.headers().firstValue("Retry-After")).isPresent();
		assertThat(new String(refusedGet.body(), java.nio.charset.StandardCharsets.UTF_8)).contains("\"RATE_LIMITED\"");
	}

	private HttpResponse<byte[]> send(String method, String token) throws Exception {
		URI uri = URI.create("http://localhost:" + this.environment.getProperty("local.server.port")
				+ IMAGE.formatted(this.product));
		return this.http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token)
			.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	/** Produit avec une image JPEG enregistrée telle quelle (le ré-encodage à l'envoi est couvert par le module produit). */
	private UUID productWithImage(byte[] jpeg) {
		return TenantContext.callAs(this.company, () -> {
			UUID category = this.jdbc.queryForObject("insert into category (name) values (?) returning id", UUID.class,
					"Catégorie G19 HEAD " + UUID.randomUUID());
			UUID id = this.jdbc.queryForObject("""
					insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
					values (?, 'Produit G19 HEAD', ?, 1000, 2000, 1800) returning id""", UUID.class,
					"G19H-" + UUID.randomUUID().toString().substring(0, 8), category);
			this.jdbc.update("""
					insert into product_image (product_id, content_type, data, size_bytes, width, height, sha256)
					values (?, 'image/jpeg', ?, ?, 8, 8, ?)""", id, jpeg, jpeg.length, sha256(jpeg));
			return id;
		});
	}

	private static byte[] jpeg(Color color) throws Exception {
		BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < 8; x++) {
			for (int y = 0; y < 8; y++) {
				image.setRGB(x, y, color.getRGB());
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}

	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Fenêtre fixe d'une minute : attendre la suivante s'il reste moins de 30 secondes (horloge de PostgreSQL). */
	private void awaitFreshWindow() {
		double remaining = this.platform.jdbc().sql("select 60 - mod(extract(epoch from clock_timestamp()), 60)::float8")
			.query(Double.class).single();
		if (remaining < 30) {
			try {
				Thread.sleep((long) (remaining * 1000) + 200);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

}
