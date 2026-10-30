package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.category.CategoryService;
import com.geststock.category.dto.CategoryRequest;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.product.ProductService;
import com.geststock.product.ProductUnit;
import com.geststock.product.dto.ProductRequest;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.support.WithPermissions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publication des événements {@code CATEGORY} et {@code PRODUCT} (lot 1, étape 4a) par les services du
 * catalogue, sur un vrai PostgreSQL : un événement par changement d'un champ exposé par le contrat
 * ({@code Category}, {@code Product} de l'OpenAPI v10), aucun sinon ; charge conforme au schéma ;
 * désactivation = {@code ARCHIVE}, réactivation = {@code UPSERT}. Les catégories et produits créés, le flux
 * et son état sont nettoyés avant et après chaque test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@WithPermissions(all = true)
class CatalogEventsIT {

	/** Liaison qui lit le flux (G4a : la disponibilité servie est celle de ses dépôts). */
	private static final LinkRef LINK = new LinkRef(UUID.randomUUID());

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String PREFIX = "ITEV-";

	@Autowired
	private CategoryService categories;

	@Autowired
	private ProductService products;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JsonMapper jsonMapper;

	private JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		cleanUp();
	}

	@AfterEach
	void cleanAfter() {
		cleanUp();
	}

	// --- CATEGORY --------------------------------------------------------------------------------------

	@Test
	void creatingACategoryPublishesAnUpsertWithTheContractPayload() {
		UUID id = this.categories.create(new CategoryRequest(PREFIX + "Cat", "Montures solaires")).id();

		List<Stored> events = events();
		assertThat(events).hasSize(1);
		Stored event = events.get(0);
		assertThat(event.type()).isEqualTo("CATEGORY");
		assertThat(event.operation()).isEqualTo("UPSERT");
		assertThat(event.objectId()).isEqualTo(id);
		assertThat(fieldNames(event.data())).containsExactlyInAnyOrder("id", "name", "description", "active", "version");
		assertThat(event.data().get("id").asString()).isEqualTo(id.toString());
		assertThat(event.data().get("name").asString()).isEqualTo(PREFIX + "Cat");
		assertThat(event.data().get("description").asString()).isEqualTo("Montures solaires");
		assertThat(event.data().get("active").asBoolean()).isTrue();
		assertThat(event.data().get("version").asLong()).isEqualTo(event.version());
	}

	@Test
	void aCategoryWithoutDescriptionPublishesANullDescription() {
		this.categories.create(new CategoryRequest(PREFIX + "Cat", null));
		JsonNode data = events().get(0).data();
		assertThat(data.has("description")).isTrue();
		assertThat(data.get("description").isNull()).isTrue();
	}

	@Test
	void changingAnExposedCategoryFieldPublishesAGreaterVersion() {
		UUID id = this.categories.create(new CategoryRequest(PREFIX + "Cat", null)).id();
		this.categories.update(id, new CategoryRequest(PREFIX + "Cat2", null));
		this.categories.update(id, new CategoryRequest(PREFIX + "Cat2", "Nouvelle description"));

		List<Stored> events = events();
		assertThat(events).extracting(Stored::operation).containsExactly("UPSERT", "UPSERT", "UPSERT");
		assertThat(events.get(1).data().get("name").asString()).isEqualTo(PREFIX + "Cat2");
		assertThat(events.get(2).data().get("description").asString()).isEqualTo("Nouvelle description");
		assertThat(events.get(1).version()).isGreaterThan(events.get(0).version());
		assertThat(events.get(2).version()).isGreaterThan(events.get(1).version());
	}

	@Test
	void aCategoryUpdateWithoutEffectPublishesNothing() {
		UUID id = this.categories.create(new CategoryRequest(PREFIX + "Cat", "Desc")).id();
		this.categories.update(id, new CategoryRequest(PREFIX + "Cat", "Desc"));
		this.categories.update(id, new CategoryRequest("  " + PREFIX + "Cat ", " Desc "));
		this.categories.setActive(id, true);
		assertThat(events()).hasSize(1);
	}

	@Test
	void deactivatingACategoryPublishesArchiveAndReactivatingPublishesUpsert() {
		UUID id = this.categories.create(new CategoryRequest(PREFIX + "Cat", null)).id();
		this.categories.setActive(id, false);
		this.categories.setActive(id, false);
		this.categories.setActive(id, true);

		List<Stored> events = events();
		assertThat(events).extracting(Stored::operation).containsExactly("UPSERT", "ARCHIVE", "UPSERT");
		assertThat(events.get(1).data().get("active").asBoolean()).isFalse();
		assertThat(events.get(2).data().get("active").asBoolean()).isTrue();
		assertThat(events.get(2).version()).isGreaterThan(events.get(1).version());
	}

	@Test
	void aDuplicateCategoryNameIsStillRefusedWithItsCodeAndPublishesNothing() {
		this.categories.create(new CategoryRequest(PREFIX + "Cat", null));
		assertThatThrownBy(() -> this.categories.create(new CategoryRequest(PREFIX + "CAT", null)))
			.isInstanceOfSatisfying(ConflictException.class,
					e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CATEGORY_NAME_ALREADY_USED));
		assertThat(events()).hasSize(1);
	}

	// --- PRODUCT ---------------------------------------------------------------------------------------

	@Test
	void creatingAProductPublishesAnUpsertWithTheContractPayload() {
		UUID category = category();
		UUID id = this.products.create(product("P1", category, "35000", "15000"), null).id();

		Stored event = only("PRODUCT");
		assertThat(event.operation()).isEqualTo("UPSERT");
		assertThat(event.objectId()).isEqualTo(id);
		JsonNode data = event.data();
		// Champs du schéma Product ; currency (C8, hypothèse) et imageSha256 (étape 4b) non remplis.
		assertThat(fieldNames(data)).containsExactlyInAnyOrder("id", "sku", "name", "description", "categoryId", "unit",
				"referencePrice", "active", "version");
		assertThat(data.get("id").asString()).isEqualTo(id.toString());
		assertThat(data.get("sku").asString()).isEqualTo(PREFIX + "P1");
		assertThat(data.get("name").asString()).isEqualTo("Monture");
		assertThat(data.get("description").isNull()).isTrue();
		assertThat(data.get("categoryId").asString()).isEqualTo(category.toString());
		assertThat(data.get("unit").asString()).isEqualTo("UNIT");
		assertThat(data.get("referencePrice").isString()).isTrue();
		assertThat(data.get("referencePrice").asString()).isEqualTo("35000.00");
		assertThat(data.get("active").asBoolean()).isTrue();
		assertThat(data.get("version").asLong()).isEqualTo(event.version());
	}

	@Test
	void theProductPayloadCarriesNeitherCurrencyNorImageFingerprint() {
		// Décision de contrat : devise exposée par la liaison et l'en-tête de l'instantané ; état de l'image par
		// les seuls événements PRODUCT_IMAGE.
		UUID id = this.products.create(product("P1", category(), "35000", "15000"), null).id();
		this.products.update(id, product("P1", category(), "36000", "15000"), null);
		this.products.setActive(id, false, null);
		assertThat(events("PRODUCT")).hasSize(3).allSatisfy(event -> {
			assertThat(event.data().has("currency")).as("currency").isFalse();
			assertThat(event.data().has("imageSha256")).as("imageSha256").isFalse();
		});
	}

	@Test
	void changingAnExposedProductFieldPublishesAGreaterVersion() {
		UUID category = category();
		UUID other = category();
		UUID id = this.products.create(product("P1", category, "35000", "15000"), null).id();
		this.products.update(id, product("P1", category, "36000.5", "15000"), null);
		this.products.update(id, product("P1", other, "36000.5", "15000"), null);

		List<Stored> events = events("PRODUCT");
		assertThat(events).hasSize(3);
		assertThat(events.get(1).data().get("referencePrice").asString()).isEqualTo("36000.50");
		assertThat(events.get(2).data().get("categoryId").asString()).isEqualTo(other.toString());
		assertThat(events.get(1).version()).isGreaterThan(events.get(0).version());
		assertThat(events.get(2).version()).isGreaterThan(events.get(1).version());
	}

	@Test
	void aProductChangeOutsideTheContractPublishesNothing() {
		UUID category = category();
		UUID id = this.products.create(product("P1", category, "35000", "15000"), null).id();
		long versionBefore = productVersion(id);

		ProductRequest internalOnly = new ProductRequest(PREFIX + "P1", "99887766554433", "Monture", null, category, null,
				null, null, new BigDecimal("16000"), new BigDecimal("35000"), new BigDecimal("30000"), 7, 20);
		this.products.update(id, internalOnly, null);
		this.products.recordPurchasePrices(Map.of(id, new BigDecimal("17000")));

		assertThat(productVersion(id)).as("modifications réelles, mais hors contrat").isGreaterThan(versionBefore);
		assertThat(events("PRODUCT")).hasSize(1);
	}

	@Test
	void aProductUpdateWithoutEffectPublishesNothing() {
		UUID category = category();
		UUID id = this.products.create(product("P1", category, "35000", "15000"), null).id();
		long versionBefore = productVersion(id);
		this.products.update(id, product("P1", category, "35000.00", "15000"), null);
		this.products.setActive(id, true, null);
		assertThat(productVersion(id)).isEqualTo(versionBefore);
		assertThat(events("PRODUCT")).hasSize(1);
	}

	@Test
	void deactivatingAProductPublishesArchiveAndReactivatingPublishesUpsert() {
		UUID category = category();
		UUID id = this.products.create(product("P1", category, "35000", "15000"), null).id();
		this.products.setActive(id, false, null);
		this.products.setActive(id, false, null);
		this.products.setActive(id, true, null);

		List<Stored> events = events("PRODUCT");
		assertThat(events).extracting(Stored::operation).containsExactly("UPSERT", "ARCHIVE", "UPSERT");
		assertThat(events.get(1).data().get("active").asBoolean()).isFalse();
		assertThat(events.get(2).data().get("active").asBoolean()).isTrue();
	}

	@Test
	void aDuplicateSkuIsStillRefusedWithItsCodeAndPublishesNothing() {
		UUID category = category();
		this.products.create(product("P1", category, "35000", "15000"), null);
		assertThatThrownBy(() -> this.products.create(product("p1", category, "1", "1"), null))
			.isInstanceOfSatisfying(ConflictException.class,
					e -> assertThat(e.getCode()).isEqualTo(ErrorCode.SKU_ALREADY_USED));
		assertThat(events("PRODUCT")).hasSize(1);
	}

	// --- T-C3-2e : verrou de ligne -----------------------------------------------------------------------

	@Test
	void aSecondTransactionWaitingForTheRowLockPublishesAGreaterSeqAndVersion() throws Exception {
		UUID category = category();
		UUID id = this.products.create(product("P1", category, "35000", "15000"), null).id();
		SecurityContext security = SecurityContextHolder.getContext();
		CountDownLatch firstUpdated = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		ExecutorService threads = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = threads.submit(() -> inTransaction(security, () -> {
				this.products.update(id, product("P1", category, "36000", "15000"), null);
				firstUpdated.countDown();
				await(releaseFirst);
			}));
			assertThat(firstUpdated.await(10, TimeUnit.SECONDS)).isTrue();

			Future<?> second = threads.submit(() -> inTransaction(security, () -> {
				// Attend le verrou de ligne tenu par la première transaction, puis relit l'état validé.
				this.jdbc.queryForObject("select id from product where id = ? for update", UUID.class, id);
				this.products.update(id, product("P1", category, "37000", "15000"), null);
			}));
			assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS)).as("la seconde attend le verrou")
				.isInstanceOf(TimeoutException.class);

			releaseFirst.countDown();
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		}
		finally {
			releaseFirst.countDown();
			threads.shutdownNow();
		}

		List<ChangeEvent> served = this.feed.read(LINK, 0, null, ChangeFilter.ALL).items().stream()
			.filter(e -> e.type() == IntegrationEventType.PRODUCT)
			.toList();
		assertThat(served).hasSize(3);
		ChangeEvent firstEvent = served.get(1);
		ChangeEvent secondEvent = served.get(2);
		assertThat(firstEvent.data().get("referencePrice").asString()).isEqualTo("36000.00");
		assertThat(secondEvent.data().get("referencePrice").asString()).isEqualTo("37000.00");
		assertThat(secondEvent.seq()).isGreaterThan(firstEvent.seq());
		assertThat(secondEvent.version()).isGreaterThan(firstEvent.version());
	}

	// --- De bout en bout ---------------------------------------------------------------------------------

	@Test
	void aCategoryThenAProductInItAreServedByTheFeedCategoryFirst() {
		UUID category = this.categories.create(new CategoryRequest(PREFIX + "Cat", null)).id();
		UUID product = this.products.create(product("P1", category, "35000", "15000"), null).id();

		ChangePage page = this.feed.read(LINK, 0, null, ChangeFilter.ALL);
		assertThat(page.items()).extracting(ChangeEvent::type)
			.containsExactly(IntegrationEventType.CATEGORY, IntegrationEventType.PRODUCT);
		assertThat(page.items()).extracting(ChangeEvent::objectId).containsExactly(category, product);
		assertThat(page.items().get(1).data().get("categoryId").asString()).isEqualTo(category.toString());
		assertThat(page.items()).allSatisfy(e -> assertThat(e.data().get("version").asLong()).isEqualTo(e.version()));
		assertThat(page.lastSeq()).isEqualTo(2L);
		assertThat(page.hasMore()).isFalse();
	}

	// --- Outils ------------------------------------------------------------------------------------------

	private UUID category() {
		return this.categories.create(new CategoryRequest(PREFIX + UUID.randomUUID().toString().substring(0, 8), null))
			.id();
	}

	private static ProductRequest product(String sku, UUID category, String retailPrice, String purchasePrice) {
		return new ProductRequest(PREFIX + sku, null, "Monture", null, category, null, null, ProductUnit.UNIT,
				new BigDecimal(purchasePrice), new BigDecimal(retailPrice), new BigDecimal("28000"), 3, null);
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

	private long productVersion(UUID id) {
		return this.jdbc.queryForObject("select version from product where id = ?", Long.class, id);
	}

	private Stored only(String type) {
		List<Stored> events = events(type);
		assertThat(events).hasSize(1);
		return events.get(0);
	}

	private List<Stored> events(String type) {
		return events().stream().filter(e -> e.type().equals(type)).toList();
	}

	private List<Stored> events() {
		return this.jdbc.query("""
				select type, operation, object_id, object_version, payload::text as payload
				from integration_outbox order by write_id""",
				(rs, row) -> new Stored(rs.getString("type"), rs.getString("operation"),
						rs.getObject("object_id", UUID.class), rs.getLong("object_version"),
						rs.getString("payload") != null ? this.jsonMapper.readTree(rs.getString("payload")) : null));
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.propertyNames().forEach(names::add);
		return names;
	}

	private void cleanUp() {
		this.jdbc.update("delete from product where sku like ?", PREFIX + "%");
		this.jdbc.update("delete from category where name like ?", PREFIX + "%");
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

	private record Stored(String type, String operation, UUID objectId, long version, JsonNode data) {
	}

}
