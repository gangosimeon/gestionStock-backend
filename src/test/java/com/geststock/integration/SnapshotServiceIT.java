package com.geststock.integration;

import static com.geststock.support.BusinessScenario.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntConsumer;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.category.CategoryService;
import com.geststock.category.dto.CategoryRequest;
import com.geststock.exception.ErrorCode;
import com.geststock.platform.PlatformDb;
import com.geststock.product.ProductImageService;
import com.geststock.product.ProductService;
import com.geststock.product.ProductUnit;
import com.geststock.product.dto.ProductRequest;
import com.geststock.sale.SaleService;
import com.geststock.sale.dto.CreateSaleRequest;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.BusinessScenario;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.CurrentTenant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Instantané cohérent du catalogue (lot 1, étape 7 ; contrat v11-D10) sur un vrai PostgreSQL : {@code asOfSeq}
 * identique sur toutes les pages, pagination stable, sections dans l'ordre du contrat, objets archivés inclus,
 * mêmes formats que les événements ; reprise du flux depuis {@code asOfSeq} sans perte (T-C3-3, T-C3-4, T-C3-14).
 * Le consommateur simulé applique la règle des versions ; l'état reconstruit est comparé à la base, lue en SQL
 * indépendamment des mappeurs.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class SnapshotServiceIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String PREFIX = "SNP-";

	@Autowired
	private SnapshotService snapshots;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private CategoryService categories;

	@Autowired
	private ProductService products;

	@Autowired
	private ProductImageService images;

	@Autowired
	private StockService stock;

	@Autowired
	private SaleService sales;

	@Autowired
	private CashService cash;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private CurrentTenant tenant;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private JsonMapper jsonMapper;

	private StockFixtures fixtures;

	private BusinessScenario scenario;

	private UUID actor;

	private UUID warehouse;

	private final LinkRef link = new LinkRef(UUID.randomUUID());

	private String storedCurrency;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.scenario = new BusinessScenario(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("instantane");
		this.warehouse = this.fixtures.warehouse("instantane");
		as(this.actor, () -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("50000"), null, null), this.warehouse));
		this.storedCurrency = companyCurrency();
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		setCompanyCurrency(this.storedCurrency);
		this.scenario.purge(List.of(this.warehouse), List.of(), List.of());
		this.jdbc.update("delete from product where sku like ?", PREFIX + "%");
		this.jdbc.update("delete from category where name like ?", PREFIX + "%");
		this.fixtures.purge();
		resetFeed();
	}

	// --- Limites ------------------------------------------------------------------------------------------

	@Test
	void theDefaultLimitIs200TheMaximum500AndBeyondIsAValidationFailure() {
		for (int i = 0; i < 70; i++) {
			this.fixtures.product("limite-" + i, 1000);
		}
		assertThat(this.snapshots.read(this.link, null, null).size()).as("200 par défaut").isEqualTo(200);
		SnapshotPage widest = this.snapshots.read(this.link, null, 500);
		assertThat(widest.size()).isLessThanOrEqualTo(500).isEqualTo(Math.min(500, catalogObjects()));

		for (int limit : new int[] { 0, 501, -1 }) {
			assertThatThrownBy(() -> this.snapshots.read(this.link, null, limit)).as("limit %d", limit)
				.isInstanceOfSatisfying(SnapshotRequestException.class,
						e -> assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
		}
	}

	// --- T-C3-3 : pages stables -----------------------------------------------------------------------------

	@Test
	void asOfSeqIsTheSameOnEveryPageAndNoObjectThatExistedIsLostOrDuplicated() {
		List<UUID> stocked = stockedProducts(12);
		Set<String> initialCategories = ids("select id from category");
		Set<String> initialProducts = ids("select id from product");
		int limit = Math.max(1, catalogObjects() / 4);

		List<SnapshotPage> pages = snapshot(limit, page -> {
			// Écritures entre les pages : création, modification, archivage.
			UUID category = createCategory("T3-" + page);
			createProduct("T3-" + page, category, "1000");
			update(stocked.get(page % 6), "2" + page + "000");
			as(this.actor, () -> this.products.setActive(stocked.get(6 + page % 6), false, null));
		});

		assertThat(pages).as("au moins trois pages").hasSizeGreaterThanOrEqualTo(3);
		assertThat(pages).extracting(SnapshotPage::asOfSeq).containsOnly(pages.get(0).asOfSeq());
		assertThat(pages.subList(0, pages.size() - 1)).allSatisfy(p -> assertThat(p.nextPageToken()).isNotNull());
		assertThat(pages.getLast().nextPageToken()).as("absent sur la dernière page").isNull();
		assertThat(pages).allSatisfy(p -> assertThat(p.size()).isLessThanOrEqualTo(limit));

		assertThat(served(pages, SnapshotPage::categories, "id")).doesNotHaveDuplicates().containsAll(initialCategories);
		assertThat(served(pages, SnapshotPage::products, "id")).doesNotHaveDuplicates().containsAll(initialProducts);
		assertThat(served(pages, SnapshotPage::productImages, "productId")).doesNotHaveDuplicates()
			.containsAll(initialProducts);
		assertThat(served(pages, SnapshotPage::availability, "productId")).doesNotHaveDuplicates()
			.containsAll(initialProducts);
		assertSectionOrder(pages);
	}

	// --- T-C3-4 : instantané + rejeu = état source ------------------------------------------------------------

	@Test
	void aSnapshotThenAReplayFromAsOfSeqRebuildsTheSourceState() {
		List<UUID> stocked = stockedProducts(10);
		this.images.upload(stocked.get(0), photo(Color.RED));
		this.images.upload(stocked.get(1), photo(Color.BLUE));
		int limit = Math.max(1, catalogObjects() / 4);

		Consumer consumer = new Consumer();
		List<SnapshotPage> pages = snapshot(limit, page -> writesDuringPagination(stocked, page));
		pages.forEach(consumer::apply);
		writesDuringPagination(stocked, 9);
		consumer.replay(this.feed, pages.get(0).asOfSeq());

		consumer.assertEqualsSource();
	}

	// --- T-C3-14 complet : curseur expiré, instantané, reprise --------------------------------------------------

	@Test
	void anExpiredCursorIsRecoveredBySnapshotAndReplayWithoutLoss() {
		List<UUID> stocked = stockedProducts(6);
		Consumer consumer = new Consumer();
		long cursor = consumer.replay(this.feed, 0);

		update(stocked.get(0), "7000");
		receive(stocked.get(1), 4);
		this.images.upload(stocked.get(2), photo(Color.GREEN));
		ChangePage tail = this.feed.read(cursor, 500, ChangeFilter.ALL);
		long purgedThrough = tail.lastSeq() - 1;
		this.jdbc.update("delete from integration_outbox where seq <= ?", purgedThrough);
		this.jdbc.update("update integration_feed_state set purged_through_seq = ?", purgedThrough);

		long expired = cursor;
		assertThatThrownBy(() -> this.feed.read(expired, 500, ChangeFilter.ALL))
			.isInstanceOfSatisfying(CursorExpiredException.class,
					e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CURSOR_EXPIRED));

		List<SnapshotPage> pages = snapshot(Math.max(1, catalogObjects() / 3), page -> {
			update(stocked.get(3), "9" + page + "00");
			sell(stocked.get(4), 1);
		});
		pages.forEach(consumer::apply);
		consumer.replay(this.feed, pages.get(0).asOfSeq());

		consumer.assertEqualsSource();
	}

	// --- Contenu ------------------------------------------------------------------------------------------------

	@Test
	void archivedObjectsAreIncludedAndAnImageWithoutGenerationHasVersionZero() {
		UUID category = createCategory("ARCH");
		UUID archived = createProduct("ARCH", category, "1000");
		as(this.actor, () -> this.products.setActive(archived, false, null));
		as(this.actor, () -> this.categories.setActive(category, false));
		UUID withImage = this.fixtures.product("image", 1000);
		this.images.upload(withImage, photo(Color.ORANGE));
		this.images.delete(withImage);

		List<SnapshotPage> pages = snapshot(500, page -> {
		});

		assertThat(entry(pages, SnapshotPage::categories, "id", category).get("active").asBoolean()).isFalse();
		assertThat(entry(pages, SnapshotPage::products, "id", archived).get("active").asBoolean()).isFalse();
		JsonNode neverHadImage = entry(pages, SnapshotPage::productImages, "productId", archived);
		assertThat(neverHadImage.get("version").asLong()).isZero();
		assertThat(neverHadImage.get("imageSha256").isNull()).isTrue();
		JsonNode removedImage = entry(pages, SnapshotPage::productImages, "productId", withImage);
		assertThat(removedImage.get("version").asLong()).isEqualTo(2L);
		assertThat(removedImage.get("imageSha256").isNull()).isTrue();
		assertThat(entry(pages, SnapshotPage::availability, "productId", archived).get("available").asInt()).isZero();
	}

	@Test
	void entriesHaveExactlyTheFormatOfTheEvents() {
		UUID category = createCategory("FMT");
		UUID product = createProduct("FMT", category, "4500");
		UUID stockedProduct = this.fixtures.product("format", 1000);
		receive(stockedProduct, 3);
		this.images.upload(stockedProduct, photo(Color.CYAN));

		Map<String, JsonNode> events = new HashMap<>();
		new Consumer().eventsSince(this.feed, 0).forEach(e -> events.put(e.typeName() + ":" + e.objectId(), e.data()));
		List<SnapshotPage> pages = snapshot(500, page -> {
		});

		// Comparaison du JSON tel qu'il sera transmis (une charge relue depuis jsonb et une charge produite par le
		// mappeur ne diffèrent que par le type Java des nombres).
		assertThat(json(entry(pages, SnapshotPage::categories, "id", category)))
			.isEqualTo(json(events.get("CATEGORY:" + category)));
		assertThat(json(entry(pages, SnapshotPage::products, "id", product))).isEqualTo(json(events.get("PRODUCT:" + product)));
		assertThat(json(entry(pages, SnapshotPage::productImages, "productId", stockedProduct)))
			.isEqualTo(json(events.get("PRODUCT_IMAGE:" + stockedProduct)));
		JsonNode availability = json(entry(pages, SnapshotPage::availability, "productId", stockedProduct));
		JsonNode event = json(events.get("AVAILABILITY:" + stockedProduct));
		assertThat(fieldNames(availability)).isEqualTo(fieldNames(event));
		assertThat(availability.get("available")).isEqualTo(event.get("available"));
		assertThat(availability.get("perWarehouse")).isEqualTo(event.get("perWarehouse"));
	}

	/** JSON transmis, relu : même représentation des nombres des deux côtés. */
	private JsonNode json(JsonNode node) {
		assertThat(node).isNotNull();
		return this.jsonMapper.readTree(this.jsonMapper.writeValueAsString(node));
	}

	// --- Devise et jeton ----------------------------------------------------------------------------------------

	@Test
	void theCurrencyIsNormalisedToIso4217AndAnUnknownValueIsRefused() {
		for (String stored : new String[] { "FCFA", "F CFA", "XOF" }) {
			setCompanyCurrency(stored);
			assertThat(this.snapshots.read(this.link, null, 1).currency()).as(stored).isEqualTo("XOF");
		}
		setCompanyCurrency("DOLLAR");
		assertThatThrownBy(() -> this.snapshots.read(this.link, null, 1))
			.isInstanceOf(UnsupportedCurrencyException.class)
			.hasMessageContaining("DOLLAR");
	}

	@Test
	void aPageTokenOfAnotherLinkIsRefused() {
		stockedProducts(2);
		SnapshotPage first = this.snapshots.read(this.link, null, 1);
		assertThat(first.nextPageToken()).isNotNull();
		assertThatThrownBy(() -> this.snapshots.read(new LinkRef(UUID.randomUUID()), first.nextPageToken(), 1))
			.isInstanceOfSatisfying(InvalidPageTokenException.class,
					e -> assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_PAGE_TOKEN));
		assertThat(this.snapshots.read(this.link, first.nextPageToken(), 1).asOfSeq()).isEqualTo(first.asOfSeq());
	}

	// --- Écritures et lecture ------------------------------------------------------------------------------------

	/**
	 * Créations, modifications, archivage et réactivation, images, ventes et réceptions ; {@code step} distingue
	 * les appels. Produits 0 à 4 : fiches et images (archivables) ; 5 à 9 : ventes et réceptions (toujours actifs).
	 */
	private void writesDuringPagination(List<UUID> stocked, int step) {
		UUID category = createCategory("W" + step);
		createProduct("W" + step, category, "1" + step + "00");
		update(stocked.get(step % 5), "3" + step + "50");
		as(this.actor, () -> this.products.setActive(stocked.get(1 + step % 4), step % 2 == 1, null));
		this.images.upload(stocked.get(step % 5), photo(new Color(20 * step % 255, 90, 140)));
		this.images.delete(stocked.get(0));
		sell(stocked.get(5 + step % 5), 1);
		receive(stocked.get(5 + (step + 1) % 5), 2);
	}

	private List<SnapshotPage> snapshot(int limit, IntConsumer betweenPages) {
		List<SnapshotPage> pages = new ArrayList<>();
		SnapshotPage page = this.snapshots.read(this.link, null, limit);
		pages.add(page);
		while (page.nextPageToken() != null) {
			betweenPages.accept(pages.size() - 1);
			page = this.snapshots.read(this.link, page.nextPageToken(), limit);
			pages.add(page);
			assertThat(pages).as("pagination finie").hasSizeLessThan(1000);
		}
		return pages;
	}

	private List<UUID> stockedProducts(int count) {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			UUID product = this.fixtures.product("snap-" + i, 1000);
			receive(product, 20);
			ids.add(product);
		}
		return ids;
	}

	private UUID createCategory(String suffix) {
		return as(this.actor, () -> this.categories.create(new CategoryRequest(PREFIX + suffix + "-"
				+ UUID.randomUUID().toString().substring(0, 6), null)).id());
	}

	private UUID createProduct(String suffix, UUID category, String retailPrice) {
		return as(this.actor, () -> this.products.create(new ProductRequest(PREFIX + suffix + "-"
				+ UUID.randomUUID().toString().substring(0, 6), null, "Produit " + suffix, null, category, null, null,
				ProductUnit.UNIT, BigDecimal.ONE, new BigDecimal(retailPrice), BigDecimal.ONE, 0, null), null).id());
	}

	private void update(UUID product, String retailPrice) {
		Map<String, Object> row = this.jdbc.queryForMap("select sku, name, category_id from product where id = ?",
				product);
		as(this.actor, () -> this.products.update(product, new ProductRequest((String) row.get("sku"), null,
				(String) row.get("name"), null, (UUID) row.get("category_id"), null, null, ProductUnit.UNIT,
				BigDecimal.ONE, new BigDecimal(retailPrice), BigDecimal.ONE, 0, null), null));
	}

	private void receive(UUID product, int quantity) {
		as(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(this.warehouse, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock")));
	}

	private void sell(UUID product, int quantity) {
		as(this.actor, () -> this.sales.create(new CreateSaleRequest(null, null, null, PaymentMethod.CASH, null, null,
				List.of(new CreateSaleRequest.Item(product, quantity, null))), this.warehouse));
	}

	private static byte[] photo(Color color) {
		BufferedImage image = new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, 48, 48);
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

	private int catalogObjects() {
		return this.jdbc.queryForObject("select (select count(*) from category) + 3 * (select count(*) from product)",
				Integer.class);
	}

	private Set<String> ids(String sql) {
		Set<String> ids = new HashSet<>();
		this.jdbc.queryForList(sql, UUID.class).forEach(id -> ids.add(id.toString()));
		return ids;
	}

	private static List<String> served(List<SnapshotPage> pages,
			java.util.function.Function<SnapshotPage, List<JsonNode>> section, String idField) {
		List<String> ids = new ArrayList<>();
		pages.forEach(page -> section.apply(page).forEach(entry -> ids.add(entry.get(idField).asString())));
		return ids;
	}

	private static JsonNode entry(List<SnapshotPage> pages, java.util.function.Function<SnapshotPage, List<JsonNode>> section,
			String idField, UUID id) {
		return pages.stream().flatMap(page -> section.apply(page).stream())
			.filter(entry -> entry.get(idField).asString().equals(id.toString()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("absent de l'instantané : " + id));
	}

	/** Sections servies dans l'ordre du contrat : aucune section après une section plus loin dans l'ordre. */
	private static void assertSectionOrder(List<SnapshotPage> pages) {
		int reached = 0;
		for (SnapshotPage page : pages) {
			List<List<JsonNode>> sections = List.of(page.categories(), page.products(), page.productImages(),
					page.availability());
			for (int section = 0; section < sections.size(); section++) {
				if (!sections.get(section).isEmpty()) {
					assertThat(section).as("ordre des sections").isGreaterThanOrEqualTo(reached);
					reached = section;
				}
			}
		}
		assertThat(reached).isEqualTo(3);
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.propertyNames().forEach(names::add);
		names.sort(null);
		return names;
	}

	private String companyCurrency() {
		return this.platform.jdbc().sql("select currency from platform.company where id = :id")
			.param("id", this.tenant.require().companyId()).query(String.class).single();
	}

	private void setCompanyCurrency(String currency) {
		this.platform.jdbc().sql("update platform.company set currency = :currency where id = :id")
			.param("currency", currency).param("id", this.tenant.require().companyId()).update();
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

	/**
	 * Consommateur simulé (boutique) : applique un objet si sa version dépasse celle qu'il détient pour le même
	 * couple (type, identifiant) ; même règle pour l'instantané et le flux.
	 */
	private final class Consumer {

		private final Map<String, Long> versions = new HashMap<>();

		private final Map<String, JsonNode> state = new HashMap<>();

		/** Produits reçus par la section products de l'instantané. */
		private final Set<String> snapshotProducts = new HashSet<>();

		void apply(SnapshotPage page) {
			page.categories().forEach(data -> offer("CATEGORY", data.get("id").asString(), data.get("version").asLong(), data));
			page.products().forEach(data -> {
				this.snapshotProducts.add(data.get("id").asString());
				offer("PRODUCT", data.get("id").asString(), data.get("version").asLong(), data);
			});
			page.productImages().forEach(data -> offer("PRODUCT_IMAGE", data.get("productId").asString(),
					data.get("version").asLong(), data));
			page.availability().forEach(data -> offer("AVAILABILITY", data.get("productId").asString(), page.asOfSeq(), data));
		}

		/** Rejoue le flux après {@code after} ; renvoie le nouveau curseur. */
		long replay(ChangeFeedService changes, long after) {
			long cursor = after;
			ChangePage page;
			do {
				page = changes.read(cursor, 500, ChangeFilter.ALL);
				page.items().forEach(e -> offer(e.typeName(), e.objectId().toString(), e.version(), e.data()));
				cursor = page.lastSeq();
			}
			while (page.hasMore());
			return cursor;
		}

		List<ChangeEvent> eventsSince(ChangeFeedService changes, long after) {
			List<ChangeEvent> events = new ArrayList<>();
			long cursor = after;
			ChangePage page;
			do {
				page = changes.read(cursor, 500, ChangeFilter.ALL);
				events.addAll(page.items());
				cursor = page.lastSeq();
			}
			while (page.hasMore());
			return events;
		}

		private void offer(String type, String id, long version, JsonNode data) {
			String key = type + ":" + id;
			Long held = this.versions.get(key);
			if (held == null || version > held) {
				this.versions.put(key, version);
				this.state.put(key, data);
			}
		}

		/** État reconstruit = base, objet par objet (lecture SQL indépendante des mappeurs). */
		void assertEqualsSource() {
			for (Map<String, Object> row : jdbc.queryForList("select id, name, active, version from category")) {
				JsonNode held = held("CATEGORY", row.get("id"));
				assertThat(held.get("name").asString()).isEqualTo(row.get("name"));
				assertThat(held.get("active").asBoolean()).isEqualTo(row.get("active"));
				assertThat(this.versions.get("CATEGORY:" + row.get("id"))).isEqualTo(row.get("version"));
			}
			for (Map<String, Object> row : jdbc.queryForList(
					"select id, sku, name, retail_price, active, version, category_id from product")) {
				JsonNode held = held("PRODUCT", row.get("id"));
				assertThat(held.get("sku").asString()).isEqualTo(row.get("sku"));
				assertThat(held.get("name").asString()).isEqualTo(row.get("name"));
				assertThat(held.get("referencePrice").asString())
					.isEqualTo(((BigDecimal) row.get("retail_price")).setScale(2, RoundingMode.HALF_UP).toPlainString());
				assertThat(held.get("active").asBoolean()).as("actif %s", row.get("sku")).isEqualTo(row.get("active"));
				assertThat(held.get("categoryId").asString()).isEqualTo(row.get("category_id").toString());
				assertThat(this.versions.get("PRODUCT:" + row.get("id"))).isEqualTo(row.get("version"));
			}
			for (Map<String, Object> row : jdbc.queryForList("""
					select p.id, i.sha256 from product p left join product_image i on i.product_id = p.id""")) {
				JsonNode held = heldOrInitial("PRODUCT_IMAGE", row.get("id"));
				String sha = held == null || held.get("imageSha256").isNull() ? null : held.get("imageSha256").asString();
				assertThat(sha).as("image de %s", row.get("id")).isEqualTo(row.get("sha256"));
			}
			for (Map<String, Object> row : jdbc.queryForList("""
					select p.id, coalesce(sum(l.quantity) filter (where w.active), 0) as available
					from product p
					left join stock_level l on l.product_id = p.id
					left join warehouse w on w.id = l.warehouse_id
					group by p.id""")) {
				JsonNode held = heldOrInitial("AVAILABILITY", row.get("id"));
				assertThat(held == null ? 0L : held.get("available").asLong()).as("disponibilité de %s", row.get("id"))
					.isEqualTo(((Number) row.get("available")).longValue());
			}
		}

		/**
		 * État image ou disponibilité d'un produit. Un produit reçu par l'instantané l'a toujours ; un produit créé
		 * pendant la pagination, après le passage de ces sections, ne l'a que si un événement l'a publié depuis :
		 * sinon il est dans son état initial (aucune image, disponibilité 0), signalé par {@code null}.
		 */
		private JsonNode heldOrInitial(String type, Object id) {
			if (this.snapshotProducts.contains(id.toString())) {
				return held(type, id);
			}
			return this.state.get(type + ":" + id);
		}

		private JsonNode held(String type, Object id) {
			JsonNode held = this.state.get(type + ":" + id);
			assertThat(held).as("%s %s reconstruit", type, id).isNotNull();
			return Objects.requireNonNull(held);
		}

	}

}
