package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.imageio.ImageIO;
import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import com.geststock.product.ProductImageService;
import com.geststock.product.ProductService;
import com.geststock.product.ProductUnit;
import com.geststock.product.dto.ProductRequest;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Génération de l'image des produits et événements {@code PRODUCT_IMAGE} (P6, P7, T-C7-1 à T-C7-6) sur un vrai
 * PostgreSQL, par le service des images de l'application. Les produits créés ({@code PIE-…}, images et
 * générations supprimées en cascade), le flux et son état sont nettoyés avant et après chaque test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@WithPermissions(all = true)
class ProductImageEventsIT {

	/** Drapeau de publication (G13), dépendance d'{@code IntegrationEvents} construit par ce test. */
	@Autowired
	private PublicationFlag publicationFlag;

	/** Liaison qui lit le flux (G4a : la disponibilité servie est celle de ses dépôts). */
	private static final LinkRef LINK = new LinkRef(UUID.randomUUID());

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private ProductImageService images;

	@Autowired
	private ProductService products;

	@Autowired
	private IntegrationEvents events;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private OutboxWriter writer;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate jdbc;

	private ExecutorService threads;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		// G13 : la publication exige une liaison active (drapeau de l'entreprise) ; ce test publie sans liaison,
		// comme au lot 1 : le drapeau est levé pour la durée du test.
		this.jdbc.update("update integration_publication set enabled = true");
		this.threads = Executors.newFixedThreadPool(2);
		cleanUp();
	}

	@AfterEach
	void tearDown() {
		this.jdbc.update("update integration_publication set enabled = false");
		this.threads.shutdownNow();
		cleanUp();
		this.jdbc.execute("drop schema if exists " + ProductImageGenerationSchemaIT.SCRATCH + " cascade");
	}

	// --- T-C7-1 : ajout, retrait, ajout --------------------------------------------------------------------

	@Test
	void addRemoveAddGivesVersionsOneTwoThreeAndTheRemovalHasANullFingerprint() {
		UUID product = product();
		String sha = this.images.upload(product, photo(Color.RED)).version();
		this.images.delete(product);
		assertThat(this.images.upload(product, photo(Color.RED)).version()).isEqualTo(sha);

		List<Stored> stored = imageEvents(product);
		assertThat(stored).extracting(Stored::version).containsExactly(1L, 2L, 3L);
		assertThat(stored).extracting(Stored::operation).containsOnly("UPSERT");
		assertThat(stored).allSatisfy(e -> {
			assertThat(fieldNames(e.data())).containsExactlyInAnyOrder("productId", "imageSha256", "version");
			assertThat(e.data().get("productId").asString()).isEqualTo(product.toString());
			assertThat(e.data().get("version").asLong()).isEqualTo(e.version());
		});
		assertThat(stored.get(0).data().get("imageSha256").asString()).isEqualTo(sha);
		assertThat(stored.get(1).data().get("imageSha256").isNull()).as("retrait : imageSha256 nul").isTrue();
		assertThat(stored.get(2).data().get("imageSha256").asString()).isEqualTo(sha);
		assertThat(generation(product)).containsEntry("generation", 3L).containsEntry("sha256", sha);
	}

	// --- T-C7-2 : deux premiers envois concurrents ------------------------------------------------------------

	@Test
	void twoConcurrentFirstUploadsNeitherFailNorSkipAGeneration() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID product = product();
			CyclicBarrier barrier = new CyclicBarrier(2);
			Future<String> first = this.threads.submit(() -> {
				barrier.await(10, TimeUnit.SECONDS);
				return this.images.upload(product, photo(Color.BLUE)).version();
			});
			Future<String> second = this.threads.submit(() -> {
				barrier.await(10, TimeUnit.SECONDS);
				return this.images.upload(product, photo(Color.GREEN)).version();
			});
			String a = first.get(20, TimeUnit.SECONDS);
			String b = second.get(20, TimeUnit.SECONDS);

			List<Stored> stored = imageEvents(product);
			assertThat(stored).as("tour %d", round).extracting(Stored::version).containsExactly(1L, 2L);
			String last = stored.get(1).data().get("imageSha256").asString();
			assertThat(last).isIn(a, b);
			assertThat(this.jdbc.queryForList("select sha256 from product_image where product_id = ?", String.class,
					product)).as("une seule image, la dernière publiée").containsExactly(last);
			assertThat(generation(product)).containsEntry("generation", 2L).containsEntry("sha256", last);
		}
	}

	// --- T-C7-3 : envoi pendant la modification de la fiche --------------------------------------------------

	@Test
	void anUploadDuringAProductEditNeitherBlocksNorConflictsNorPublishesAProductEvent() throws Exception {
		UUID product = product();
		SecurityContext security = SecurityContextHolder.getContext();
		CountDownLatch edited = new CountDownLatch(1);
		CountDownLatch releaseEdit = new CountDownLatch(1);
		Future<?> edit = this.threads.submit(() -> inTransaction(security, () -> {
			this.products.update(product, productRequest(product, "9000"), null);
			edited.countDown();
			await(releaseEdit);
		}));
		try {
			assertThat(edited.await(10, TimeUnit.SECONDS)).isTrue();
			Future<String> upload = this.threads.submit(() -> this.images.upload(product, photo(Color.ORANGE)).version());
			assertThat(upload.get(5, TimeUnit.SECONDS)).as("l'envoi n'attend pas la fiche").isNotNull();
			assertThat(productVersion(product)).as("fiche non validée, image sans effet").isZero();
		}
		finally {
			releaseEdit.countDown();
		}
		edit.get(10, TimeUnit.SECONDS);

		assertThat(productVersion(product)).as("seule la modification de la fiche").isEqualTo(1L);
		assertThat(eventTypes(product)).containsExactlyInAnyOrder("PRODUCT", "PRODUCT_IMAGE");

		long before = productVersion(product);
		this.images.upload(product, photo(Color.PINK));
		assertThat(productVersion(product)).isEqualTo(before);
		assertThat(eventTypes(product)).filteredOn("PRODUCT"::equals).hasSize(1);
	}

	// --- T-C7-4 : annulation --------------------------------------------------------------------------------

	@Test
	void aRolledBackImageTransactionLeavesNeitherGenerationNorEvent() {
		UUID product = product();
		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> {
			this.images.upload(product, photo(Color.CYAN));
			status.setRollbackOnly();
		});
		assertThat(generationCount(product)).isZero();
		assertThat(imageEvents(product)).isEmpty();
		assertThat(this.jdbc.queryForObject("select count(*) from product_image where product_id = ?", Integer.class,
				product)).isZero();
	}

	// --- T-C7-5 et T-C7-6 : sans effet (P6) ------------------------------------------------------------------

	@Test
	void theSameImageAgainKeepsTheGenerationAndPublishesNothing() {
		UUID product = product();
		this.images.upload(product, photo(Color.MAGENTA));
		this.images.upload(product, photo(Color.MAGENTA));
		this.images.upload(product, photo(Color.YELLOW));
		this.images.upload(product, photo(Color.YELLOW));
		assertThat(imageEvents(product)).extracting(Stored::version).containsExactly(1L, 2L);
		assertThat(generation(product)).containsEntry("generation", 2L);
	}

	@Test
	void removingAMissingImageCreatesNoGenerationAndPublishesNothing() {
		UUID product = product();
		this.images.delete(product);
		assertThat(generationCount(product)).as("aucune ligne créée").isZero();
		assertThat(imageEvents(product)).isEmpty();

		this.images.upload(product, photo(Color.GRAY));
		this.images.delete(product);
		this.images.delete(product);
		assertThat(imageEvents(product)).extracting(Stored::version).containsExactly(1L, 2L);
		assertThat(generation(product)).containsEntry("generation", 2L).containsEntry("sha256", null);
	}

	// --- Verrou de la ligne de génération ---------------------------------------------------------------------

	@Test
	void anUnchangedImageStillLocksTheGenerationAndSerialisesTheNextUpload() throws Exception {
		UUID product = product();
		this.images.upload(product, photo(Color.RED));
		SecurityContext security = SecurityContextHolder.getContext();
		CountDownLatch sent = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Future<?> unchanged = this.threads.submit(() -> inTransaction(security, () -> {
			this.images.upload(product, photo(Color.RED));
			sent.countDown();
			await(release);
		}));
		try {
			assertThat(sent.await(10, TimeUnit.SECONDS)).isTrue();
			Future<String> next = this.threads.submit(() -> this.images.upload(product, photo(Color.BLUE)).version());
			assertThatThrownBy(() -> next.get(500, TimeUnit.MILLISECONDS)).as("verrou tenu sans mise à jour")
				.isInstanceOf(TimeoutException.class);
			release.countDown();
			unchanged.get(10, TimeUnit.SECONDS);
			next.get(10, TimeUnit.SECONDS);
		}
		finally {
			release.countDown();
		}
		assertThat(imageEvents(product)).extracting(Stored::version).containsExactly(1L, 2L);
	}

	// --- Image antérieure à la migration -----------------------------------------------------------------------

	@Test
	void anImageThatExistedBeforeTheMigrationGetsGenerationTwoAtItsNextChange() throws Exception {
		String scratch = ProductImageGenerationSchemaIT.SCRATCH;
		this.jdbc.execute("drop schema if exists " + scratch + " cascade");
		ProductImageGenerationSchemaIT.migrate(this.dataSource, "47");
		UUID product = ProductImageGenerationSchemaIT.insertProduct(this.jdbc, scratch + ".");
		ProductImageGenerationSchemaIT.insertImage(this.jdbc, scratch + ".", product, "a".repeat(64));
		ProductImageGenerationSchemaIT.migrate(this.dataSource, null);

		try (Connection connection = this.dataSource.getConnection()) {
			try (Statement statement = connection.createStatement()) {
				statement.execute("set search_path to " + scratch);
			}
			NamedParameterJdbcTemplate scratchJdbc = new NamedParameterJdbcTemplate(
					new SingleConnectionDataSource(connection, true));
			Long next = scratchJdbc.queryForObject(OutboxWriter.ADVANCE_ON_UPLOAD,
					new MapSqlParameterSource("productId", product).addValue("sha256", "b".repeat(64)), Long.class);
			assertThat(next).isEqualTo(2L);
			try (Statement statement = connection.createStatement()) {
				statement.execute("reset search_path");
			}
		}
	}

	// --- Point d'entrée et interrupteur --------------------------------------------------------------------------

	@Test
	void publishingAnImageOutsideATransactionFails() {
		UUID product = product();
		assertThatThrownBy(() -> this.events.publishProductImage(product, "a".repeat(64)))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(generationCount(product)).isZero();
	}

	@Test
	void aDisabledSwitchStillAdvancesTheGenerationButWritesNoEvent() {
		UUID product = product();
		IntegrationEvents disabled = new IntegrationEvents(
				new IntegrationProperties(new IntegrationProperties.Publish(false, DataSize.ofKilobytes(16))), this.writer,
				this.jsonMapper, this.entityManager, this.publicationFlag);
		new TransactionTemplate(this.transactionManager)
			.executeWithoutResult(status -> disabled.publishProductImage(product, "c".repeat(64)));
		assertThat(generation(product)).containsEntry("generation", 1L).containsEntry("sha256", "c".repeat(64));
		assertThat(imageEvents(product)).isEmpty();
	}

	// --- De bout en bout ----------------------------------------------------------------------------------------

	@Test
	void theFeedServesTheProductImageEvent() {
		UUID product = product();
		String sha = this.images.upload(product, photo(Color.WHITE)).version();

		ChangePage page = this.feed.read(LINK, 0, null, ChangeFilter.ALL);
		assertThat(page.items()).hasSize(1);
		ChangeEvent event = page.items().get(0);
		assertThat(event.type()).isEqualTo(IntegrationEventType.PRODUCT_IMAGE);
		assertThat(event.objectId()).isEqualTo(product);
		assertThat(event.operation()).isEqualTo(IntegrationOperation.UPSERT);
		assertThat(event.version()).isEqualTo(1L);
		assertThat(event.data().get("imageSha256").asString()).isEqualTo(sha);
		assertThat(event.data().get("version").asLong()).isEqualTo(1L);
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private UUID product() {
		return this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, retail_price)
				select ?, 'Photo', id, 8000 from category where name = 'Montures' returning id""", UUID.class,
				"PIE-" + UUID.randomUUID().toString().substring(0, 8));
	}

	private ProductRequest productRequest(UUID product, String retailPrice) {
		Map<String, Object> row = this.jdbc.queryForMap("select sku, category_id from product where id = ?", product);
		return new ProductRequest((String) row.get("sku"), null, "Photo", null, (UUID) row.get("category_id"), null, null,
				ProductUnit.UNIT, BigDecimal.ZERO, new BigDecimal(retailPrice), BigDecimal.ZERO, 0, null);
	}

	private static byte[] photo(Color color) {
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 64, 64);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, "png", out);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	private void inTransaction(SecurityContext security, Runnable work) {
		SecurityContextHolder.setContext(security);
		try {
			new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> work.run());
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("délai dépassé");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private long productVersion(UUID product) {
		return this.jdbc.queryForObject("select version from product where id = ?", Long.class, product);
	}

	private Map<String, Object> generation(UUID product) {
		return this.jdbc.queryForMap("select generation, sha256 from product_image_generation where product_id = ?",
				product);
	}

	private int generationCount(UUID product) {
		return this.jdbc.queryForObject("select count(*) from product_image_generation where product_id = ?",
				Integer.class, product);
	}

	private List<String> eventTypes(UUID product) {
		return this.jdbc.queryForList("select type from integration_outbox where object_id = ? order by write_id",
				String.class, product);
	}

	private List<Stored> imageEvents(UUID product) {
		return this.jdbc.query("""
				select operation, object_version, payload::text as payload from integration_outbox
				where type = 'PRODUCT_IMAGE' and object_id = ? order by write_id""",
				(rs, row) -> new Stored(rs.getString("operation"), rs.getLong("object_version"),
						this.jsonMapper.readTree(rs.getString("payload"))),
				product);
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.propertyNames().forEach(names::add);
		return names;
	}

	private void cleanUp() {
		this.jdbc.update("delete from product where sku like 'PIE-%'");
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

	private record Stored(String operation, long version, JsonNode data) {
	}

}
