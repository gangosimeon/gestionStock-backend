package com.geststock.settings;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.geststock.common.security.Permissions;
import com.geststock.settings.dto.InvoiceSettings;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Configuration de la facture de bout en bout sur PostgreSQL : valeurs par défaut de V035,
 * enregistrement et relecture, validation serveur, réinitialisation, logo (type réel, poids,
 * dimensions, retrait), droits et journal d'audit. Chaque test s'exécute dans une transaction annulée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class InvoiceSettingsApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID user;

	@BeforeEach
	void setUp() {
		this.user = this.jdbc.queryForObject(
				"insert into app_user (username, first_name, last_name) values ('invoice_settings_user', 'Awa', 'Koné') returning id",
				UUID.class);
		// État de départ connu, quel que soit ce qu'un essai manuel a laissé dans la base de test.
		this.jdbc.update("delete from invoice_logo");
	}

	// --- Lecture et valeurs par défaut --------------------------------------------------------------

	@Test
	void migrationDefaultsMatchTheApplicationDefaults() throws Exception {
		// Ligne recréée avec les seules valeurs DEFAULT des colonnes de V035 (transaction annulée ensuite).
		this.jdbc.update("delete from invoice_setting");
		this.jdbc.update("insert into invoice_setting default values");
		JsonNode current = read(get("/api/settings/invoice").with(admin()));

		JsonNode expected = this.json.valueToTree(InvoiceSettings.defaults());
		for (String section : new String[] { "company", "header", "footer", "appearance" }) {
			assertThat(current.get(section)).as(section).isEqualTo(expected.get(section));
		}
		// Les colonnes de V035 et InvoiceSettings.defaults() doivent rester identiques.
		assertThat(this.jdbc.queryForObject("""
				select count(*) from information_schema.columns
				where table_name = 'invoice_setting' and column_default is not null
				  and column_name not in ('id', 'singleton', 'version', 'created_at', 'updated_at')""", Integer.class))
			.isEqualTo(countLeaves(expected));
		assertThat(current.get("logo").isNull()).isTrue();
		assertThat(current.get("appearance").get("template").asString()).isEqualTo("MODERN");
		assertThat(current.get("footer").get("thankYouMessage").asString()).isEqualTo("Merci pour votre confiance !");
		// Aucune coordonnée fictive : une entreprise non configurée n'affiche rien.
		assertThat(current.get("company").get("companyName").asString()).isEmpty();
	}

	@Test
	void sellersReadTheConfigurationButOnlyTheAdministratorChangesIt() throws Exception {
		String body = this.json.writeValueAsString(InvoiceSettings.defaults());

		this.mvc.perform(get("/api/settings/invoice").with(actor(Permissions.SALE_CREATE))).andExpect(status().isOk());
		this.mvc.perform(get("/api/settings/invoice").with(actor(Permissions.SALE_READ))).andExpect(status().isOk());
		this.mvc.perform(get("/api/settings/invoice").with(actor(Permissions.STOCK_READ)))
			.andExpect(status().isForbidden());

		RequestPostProcessor manager = actor(Permissions.SALE_READ, Permissions.SALE_CREATE, Permissions.SALE_CANCEL,
				Permissions.ALERT_CONFIGURE, Permissions.USER_MANAGE);
		this.mvc.perform(put("/api/settings/invoice").with(manager).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		this.mvc.perform(post("/api/settings/invoice/reset").with(manager)).andExpect(status().isForbidden());
		this.mvc.perform(multipart("/api/settings/invoice/logo").file(png(40, 20)).with(manager))
			.andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/settings/invoice/logo").with(manager)).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/settings/invoice")).andExpect(status().isUnauthorized());

		assertThat(this.jdbc.queryForObject("select count(*) from invoice_logo", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log
				where entity_type = 'INVOICE_SETTING' and user_id = ? and status = 'FAILURE'""", Integer.class, this.user))
			.as("refus tracés").isEqualTo(4);
	}

	// --- Enregistrement -----------------------------------------------------------------------------

	@Test
	void savedSettingsArePersistedNormalisedAndTraced() throws Exception {
		ObjectNode body = defaults();
		ObjectNode company = (ObjectNode) body.get("company");
		company.put("companyName", "  Quincaillerie Moderne  ");
		company.put("phone", "+226 70 12 34 56");
		company.put("email", "contact@quincaillerie.bf");
		company.put("taxId", "00012345X");
		company.remove("slogan"); // absent : vaut vide
		((ObjectNode) body.get("header")).put("showWarehouse", true).put("logoPosition", "TOP");
		((ObjectNode) body.get("footer")).put("returnPolicy", "Retour sous 7 jours avec le ticket.")
			.put("showPromoMessage", false)
			.put("signatureName", "  Awa Koné  ");
		((ObjectNode) body.get("appearance")).put("template", "COMPACT")
			.put("primaryColor", "#0f766e")
			.put("dateFormat", "d MMMM yyyy")
			.put("thousandsSeparator", "DOT")
			.put("decimals", 2)
			.put("showSku", false)
			.put("tableHeaderAlignment", "RIGHT")
			.put("fontFamily", "TIMES_NEW_ROMAN");

		this.mvc.perform(put("/api/settings/invoice").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(body.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.company.companyName").value("Quincaillerie Moderne"))
			.andExpect(jsonPath("$.company.slogan").value(""))
			.andExpect(jsonPath("$.appearance.primaryColor").value("#0F766E"))
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());

		JsonNode saved = read(get("/api/settings/invoice").with(actor(Permissions.SALE_READ)));
		assertThat(saved.get("company").get("taxId").asString()).isEqualTo("00012345X");
		assertThat(saved.get("header").get("showWarehouse").asBoolean()).isTrue();
		assertThat(saved.get("header").get("logoPosition").asString()).isEqualTo("TOP");
		assertThat(saved.get("footer").get("returnPolicy").asString()).isEqualTo("Retour sous 7 jours avec le ticket.");
		assertThat(saved.get("footer").get("showPromoMessage").asBoolean()).isFalse();
		assertThat(saved.get("footer").get("signatureName").asString()).isEqualTo("Awa Koné");
		assertThat(saved.get("appearance").get("template").asString()).isEqualTo("COMPACT");
		assertThat(saved.get("appearance").get("dateFormat").asString()).isEqualTo("d MMMM yyyy");
		assertThat(saved.get("appearance").get("thousandsSeparator").asString()).isEqualTo("DOT");
		assertThat(saved.get("appearance").get("decimals").asInt()).isEqualTo(2);
		assertThat(saved.get("appearance").get("showSku").asBoolean()).isFalse();
		assertThat(saved.get("appearance").get("tableHeaderAlignment").asString()).isEqualTo("RIGHT");
		assertThat(saved.get("appearance").get("fontFamily").asString()).isEqualTo("TIMES_NEW_ROMAN");

		String after = this.jdbc.queryForObject("""
				select after_state::text from audit_log
				where action = 'UPDATE' and entity_type = 'INVOICE_SETTING' and user_id = ? and status = 'SUCCESS'
				  and module = 'SYSTEM'""", String.class, this.user);
		assertThat(after).contains("Quincaillerie Moderne");
	}

	@Test
	void invalidSettingsAreRejectedWithoutAnyChange() throws Exception {
		String before = read(get("/api/settings/invoice").with(admin())).toString();

		expectInvalid(with(defaults(), "company", "email", "pas-un-email"));
		expectInvalid(with(defaults(), "company", "phone", "appelez-moi"));
		expectInvalid(with(defaults(), "company", "companyName", "x".repeat(151)));
		expectInvalid(with(defaults(), "company", "website", "pas un site"));
		expectInvalid(with(defaults(), "footer", "salesTerms", "x".repeat(1001)));
		expectInvalid(with(defaults(), "appearance", "primaryColor", "bleu"));
		expectInvalid(with(defaults(), "appearance", "primaryColor", "#12345"));
		expectInvalid(with(defaults(), "appearance", "dateFormat", "yyyy"));
		expectInvalid(with(defaults(), "appearance", "currencyLabel", "EUR"));
		expectInvalid(with(defaults(), "appearance", "decimals", "3"));
		expectInvalid(with(defaults(), "appearance", "template", "FANCY"));
		expectInvalid(with(defaults(), "appearance", "tableHeaderAlignment", "DIAGONAL"));
		expectInvalid(with(defaults(), "appearance", "fontFamily", "COMIC_SANS"));
		ObjectNode noAppearance = defaults();
		noAppearance.remove("appearance");
		expectInvalid(noAppearance);

		assertThat(read(get("/api/settings/invoice").with(admin())).toString()).isEqualTo(before);
	}

	@Test
	void resetRestoresTheDefaultsAndKeepsTheLogo() throws Exception {
		this.mvc.perform(multipart("/api/settings/invoice/logo").file(png(120, 60)).with(admin()))
			.andExpect(status().isOk());
		this.mvc.perform(put("/api/settings/invoice").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(with(defaults(), "company", "companyName", "Boutique Test").toString()))
			.andExpect(status().isOk());

		this.mvc.perform(post("/api/settings/invoice/reset").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.company.companyName").value(""))
			.andExpect(jsonPath("$.logo.width").value(120));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log
				where action = 'RESET' and entity_type = 'INVOICE_SETTING' and user_id = ? and status = 'SUCCESS'""",
				Integer.class, this.user)).isEqualTo(1);
	}

	// --- Logo ---------------------------------------------------------------------------------------

	@Test
	void pngAndJpegLogosAreStoredReplacedServedAndRemoved() throws Exception {
		MockMultipartFile png = png(300, 100);
		this.mvc.perform(multipart("/api/settings/invoice/logo").file(png).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contentType").value("image/png"))
			.andExpect(jsonPath("$.width").value(300))
			.andExpect(jsonPath("$.height").value(100))
			.andExpect(jsonPath("$.sizeBytes").value(png.getBytes().length));

		byte[] served = this.mvc.perform(get("/api/settings/invoice/logo").with(actor(Permissions.SALE_CREATE)))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/png"))
			.andExpect(header().exists("ETag"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(served).isEqualTo(png.getBytes());

		// Un JPEG remplace le PNG : toujours une seule ligne.
		this.mvc.perform(multipart("/api/settings/invoice/logo").file(jpeg(64, 64)).with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.contentType").value("image/jpeg"));
		assertThat(this.jdbc.queryForObject("select count(*) from invoice_logo", Integer.class)).isEqualTo(1);
		this.mvc.perform(get("/api/settings/invoice").with(admin()))
			.andExpect(jsonPath("$.logo.contentType").value("image/jpeg"))
			.andExpect(jsonPath("$.logo.sha256").isNotEmpty());

		this.mvc.perform(delete("/api/settings/invoice/logo").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ok").value(true));
		this.mvc.perform(get("/api/settings/invoice/logo").with(admin()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("LOGO_NOT_FOUND"));
		this.mvc.perform(get("/api/settings/invoice").with(admin())).andExpect(jsonPath("$.logo").isEmpty());
		// Sans logo : retrait sans effet, pas d'erreur.
		this.mvc.perform(delete("/api/settings/invoice/logo").with(admin())).andExpect(status().isOk());

		assertThat(this.jdbc.queryForObject("""
				select string_agg(action, ',' order by action) from audit_log
				where entity_type = 'INVOICE_SETTING' and user_id = ? and status = 'SUCCESS'""", String.class, this.user))
			.isEqualTo("LOGO_DELETE,LOGO_UPLOAD,LOGO_UPLOAD");
	}

	@Test
	void logosAreCheckedOnTheirRealContent() throws Exception {
		// Texte déguisé en PNG : type déclaré et extension mentent, la signature est vérifiée.
		expectInvalidLogo(new MockMultipartFile("file", "logo.png", "image/png", "<svg onload=alert(1)>".getBytes()),
				"INVALID_LOGO");
		// GIF : image valide, mais format non accepté.
		expectInvalidLogo(image("gif", 10, 10, "logo.gif", "image/gif"), "INVALID_LOGO");
		// Signature PNG suivie de données corrompues.
		byte[] truncated = Arrays.copyOf(png(50, 50).getBytes(), 40);
		expectInvalidLogo(new MockMultipartFile("file", "logo.png", "image/png", truncated), "INVALID_LOGO");
		// Dimensions excessives.
		expectInvalidLogo(png(4001, 10), "INVALID_LOGO");
		// Fichier vide.
		expectInvalidLogo(new MockMultipartFile("file", "logo.png", "image/png", new byte[0]), "INVALID_LOGO");

		assertThat(this.jdbc.queryForObject("select count(*) from invoice_logo", Integer.class)).isZero();
	}

	@Test
	void oversizedLogosAreRejected() throws Exception {
		byte[] big = new byte[LogoImage.MAX_BYTES + 1];
		System.arraycopy(png(10, 10).getBytes(), 0, big, 0, 8);
		this.mvc.perform(multipart("/api/settings/invoice/logo")
			.file(new MockMultipartFile("file", "logo.png", "image/png", big)).with(admin()))
			.andExpect(status().is(413))
			.andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
		this.mvc.perform(multipart("/api/settings/invoice/logo").with(admin()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_LOGO"));
		assertThat(this.jdbc.queryForObject("select count(*) from invoice_logo", Integer.class)).isZero();
	}

	// --- Outils -------------------------------------------------------------------------------------

	private void expectInvalid(JsonNode body) throws Exception {
		this.mvc.perform(put("/api/settings/invoice").with(admin()).contentType(MediaType.APPLICATION_JSON)
			.content(body.toString()))
			.andExpect(status().isBadRequest());
	}

	private void expectInvalidLogo(MockMultipartFile file, String code) throws Exception {
		this.mvc.perform(multipart("/api/settings/invoice/logo").file(file).with(admin()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(code));
	}

	private ObjectNode defaults() {
		return (ObjectNode) this.json.valueToTree(InvoiceSettings.defaults());
	}

	private static ObjectNode with(ObjectNode body, String section, String field, String value) {
		((ObjectNode) body.get(section)).put(field, value);
		return body;
	}

	private static int countLeaves(JsonNode sections) {
		int count = 0;
		for (String section : new String[] { "company", "header", "footer", "appearance" }) {
			count += sections.get(section).size();
		}
		return count;
	}

	private JsonNode read(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		return this.json.readTree(
				this.mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private static MockMultipartFile png(int width, int height) throws Exception {
		return image("png", width, height, "logo.png", "image/png");
	}

	private static MockMultipartFile jpeg(int width, int height) throws Exception {
		return image("jpeg", width, height, "logo.jpg", "image/jpeg");
	}

	private static MockMultipartFile image(String format, int width, int height, String name, String type)
			throws Exception {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, 0x1E3A8A);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertThat(ImageIO.write(image, format, out)).isTrue();
		return new MockMultipartFile("file", name, type, out.toByteArray());
	}

	private RequestPostProcessor admin() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.user, permissions));
	}

}
