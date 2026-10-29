package com.geststock.integration;

import static com.geststock.support.BusinessScenario.as;
import static com.geststock.support.BusinessScenario.refusal;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.exception.ErrorCode;
import com.geststock.inventory.InventoryScope;
import com.geststock.inventory.InventoryService;
import com.geststock.inventory.dto.CreateInventoryRequest;
import com.geststock.inventory.dto.InventoryCountsRequest;
import com.geststock.purchase.GoodsReceiptService;
import com.geststock.purchase.PurchaseOrderService;
import com.geststock.purchase.dto.CreatePurchaseOrderRequest;
import com.geststock.purchase.dto.ReceivePurchaseOrderRequest;
import com.geststock.returns.CustomerReturnService;
import com.geststock.returns.ItemCondition;
import com.geststock.returns.ReturnSettlement;
import com.geststock.returns.dto.CreateCustomerReturnRequest;
import com.geststock.sale.SaleService;
import com.geststock.sale.dto.CancelSaleRequest;
import com.geststock.sale.dto.CreateSaleRequest;
import com.geststock.sale.dto.SaleResponse;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovement;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.stock.StockTransferService;
import com.geststock.stock.dto.TransferStockRequest;
import com.geststock.support.BusinessScenario;
import com.geststock.support.PostgresTestConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Événements {@code AVAILABILITY} (lot 1, étape 4c) sur un vrai PostgreSQL, par les services métier : un
 * événement mince par produit touché et par transaction, émis par le point unique d'écriture du stock, pour
 * chaque chemin qui modifie le stock ; valeur servie = stock réel des dépôts actifs, calculée à la lecture.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class AvailabilityEventsIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private PurchaseOrderService orders;

	@Autowired
	private GoodsReceiptService receipts;

	@Autowired
	private StockService stock;

	@Autowired
	private StockTransferService transfers;

	@Autowired
	private SaleService sales;

	@Autowired
	private CustomerReturnService returns;

	@Autowired
	private InventoryService inventories;

	@Autowired
	private CashService cash;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private FeedSequencer sequencer;

	@Autowired
	private AvailabilityProvider availability;

	@Autowired
	private ChangeFeedProperties feedProperties;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private BusinessScenario scenario;

	/** Vendeur du magasin (et auteur des autres opérations). */
	private UUID actor;

	/** Vendeur du dépôt : une seule caisse ouverte par utilisateur. */
	private UUID depotSeller;

	private UUID store;

	private UUID depot;

	private UUID supplier;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.scenario = new BusinessScenario(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("dispo");
		this.depotSeller = this.fixtures.user("dispo-depot");
		this.store = this.fixtures.warehouse("magasin");
		this.depot = this.fixtures.warehouse("depot");
		this.supplier = this.jdbc.queryForObject("insert into supplier (code, name) values (?, 'Fournisseur dispo') "
				+ "returning id", UUID.class, "FOU-" + this.store.toString().substring(0, 8).toUpperCase());
		as(this.actor, () -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("50000"), null, null), this.store));
		as(this.depotSeller,
				() -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("50000"), null, null), this.depot));
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		this.transactions.executeWithoutResult(status -> {
			UUID[] warehouses = { this.store, this.depot };
			this.jdbc.update("""
					delete from stock_transfer_line where transfer_id in
					    (select id from stock_transfer where from_warehouse_id = any(?) or to_warehouse_id = any(?))""",
					warehouses, warehouses);
			this.jdbc.update("delete from stock_transfer where from_warehouse_id = any(?) or to_warehouse_id = any(?)",
					warehouses, warehouses);
		});
		this.scenario.purge(List.of(this.store, this.depot), List.of(), List.of(this.supplier));
		this.fixtures.purge();
		resetFeed();
	}

	// --- Une vente : un événement par produit -----------------------------------------------------------------

	@Test
	void aSaleOfThreeLinesOnThreeProductsPublishesExactlyThreeEvents() {
		UUID a = stocked(this.store, 10);
		UUID b = stocked(this.store, 10);
		UUID c = stocked(this.store, 10);
		resetFeed();

		sell(this.store, item(a, 1), item(b, 2), item(c, 3));

		assertThat(availabilityEvents()).containsExactlyInAnyOrder(a, b, c);
	}

	@Test
	void oneEventPerProductAndPerTransactionEvenWithSeveralMovements() {
		UUID a = stocked(this.store, 10);
		UUID b = stocked(this.store, 10);
		UUID c = stocked(this.store, 10);
		resetFeed();

		// Deux ventes dans la même transaction : 4 lignes, 3 produits distincts.
		this.transactions.executeWithoutResult(status -> {
			sell(this.store, item(a, 1), item(b, 1));
			sell(this.store, item(a, 1), item(c, 1));
		});
		assertThat(availabilityEvents()).containsExactlyInAnyOrder(a, b, c);

		// Transfert : sortie et entrée du même produit, un seul événement.
		resetFeed();
		as(this.actor, () -> this.transfers.create(this.store, this.depot, List.of(new TransferStockRequest.Item(a, 2)),
				"Réassort"));
		assertThat(availabilityEvents()).containsExactly(a);
	}

	// --- Chaque chemin qui modifie le stock ------------------------------------------------------------------

	@Test
	void everyPathThatChangesTheStockPublishesItsEvent() {
		UUID product = this.fixtures.product("monture", 15000);

		// Réception d'achat.
		UUID order = as(this.actor, () -> this.orders.create(new CreatePurchaseOrderRequest(this.supplier, null, null,
				null, null, List.of(new CreatePurchaseOrderRequest.Line(product, 10, new BigDecimal("16000")))),
				this.store).id());
		assertOneEvent(product, () -> as(this.actor, () -> this.receipts.receive(order,
				new ReceivePurchaseOrderRequest(null, "BL-1", null, null), this.store)));

		// Transfert, puis retour du transfert.
		UUID transfer = assertOneEvent(product, () -> as(this.actor, () -> this.transfers.create(this.store, this.depot,
				List.of(new TransferStockRequest.Item(product, 3)), "Réassort")).transfer().id());
		assertOneEvent(product, () -> as(this.actor, () -> this.transfers.returnToOrigin(transfer, "Erreur", this.depot)));

		// Vente, retour client, annulation d'une vente.
		SaleResponse sale = assertOneEvent(product, () -> sell(this.store, item(product, 2)));
		assertOneEvent(product, () -> as(this.actor, () -> this.returns.create(sale.id(), new CreateCustomerReturnRequest(
				ReturnSettlement.REFUND, null, "Taille", List.of(new CreateCustomerReturnRequest.Item(product, 1,
						ItemCondition.RESELLABLE))), this.store)));
		SaleResponse mistaken = assertOneEvent(product, () -> sell(this.store, item(product, 1)));
		assertOneEvent(product, () -> as(this.actor, () -> this.sales.cancel(mistaken.id(),
				new CancelSaleRequest("Erreur de saisie"), this.store)));

		// Mouvement manuel, puis son inversion.
		StockMovement loss = assertOneEvent(product, () -> as(this.actor, () -> this.stock.applyManualMovement(
				StockMovementCommand.of(this.store, product, StockMovementType.LOSS, -1, StockSourceType.MANUAL, "Casse"))));
		assertOneEvent(product, () -> as(this.actor, () -> this.stock.reverse(loss.getId(), "Erreur de saisie")));

		// Inventaire validé avec écart.
		UUID inventory = as(this.actor, () -> this.inventories.create(new CreateInventoryRequest(null,
				InventoryScope.SELECTION, null, List.of(product), null), this.store).id());
		as(this.actor, () -> this.inventories.start(inventory, this.store));
		as(this.actor, () -> this.inventories.recordCounts(inventory,
				List.of(new InventoryCountsRequest.Count(product, 5, null)), this.store));
		as(this.actor, () -> this.inventories.submit(inventory, this.store));
		assertOneEvent(product, () -> as(this.actor, () -> this.inventories.validate(inventory, "Écart", this.store)));

		assertThat(served(product).get("available").asInt()).isEqualTo(stockOf(product)).isEqualTo(5);
	}

	// --- Annulations ---------------------------------------------------------------------------------------------

	@Test
	void aRolledBackOrRefusedSalePublishesNothing() {
		UUID a = stocked(this.store, 2);
		resetFeed();

		this.transactions.executeWithoutResult(status -> {
			sell(this.store, item(a, 1));
			status.setRollbackOnly();
		});
		assertThat(availabilityEvents()).as("vente annulée par rollback").isEmpty();

		assertThat(refusal(() -> sell(this.store, item(a, 5)))).isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
		assertThat(availabilityEvents()).as("vente refusée").isEmpty();
	}

	// --- T-C3-13 : deux ventes concurrentes dans deux dépôts --------------------------------------------------

	@Test
	void twoConcurrentSalesInTwoWarehousesServeTheRealStock() throws Exception {
		for (int round = 0; round < 5; round++) {
			UUID product = this.fixtures.product("monture", 15000);
			receive(this.store, product, 10);
			receive(this.depot, product, 10);
			CyclicBarrier barrier = new CyclicBarrier(2);
			List<Callable<SaleResponse>> tasks = List.of(() -> {
				barrier.await(10, TimeUnit.SECONDS);
				return sell(this.store, item(product, 3));
			}, () -> {
				barrier.await(10, TimeUnit.SECONDS);
				return sell(this.depot, item(product, 4));
			});
			BusinessScenario.runConcurrently(tasks);

			JsonNode value = served(product);
			assertThat(value.get("available").asInt()).as("tour %d", round).isEqualTo(13).isEqualTo(stockOf(product));
			int sum = 0;
			for (JsonNode warehouse : value.get("perWarehouse")) {
				int expected = warehouse.get("warehouseId").asString().equals(this.store.toString()) ? 7 : 6;
				assertThat(warehouse.get("available").asInt()).isEqualTo(expected);
				sum += warehouse.get("available").asInt();
			}
			assertThat(sum).isEqualTo(13);
		}
	}

	// --- Page de 500 événements : un seul appel du fournisseur ---------------------------------------------------

	@Test
	void aPageOfFiveHundredAvailabilityEventsCallsTheProviderOnce() {
		for (int i = 0; i < 500; i++) {
			this.jdbc.update("""
					insert into integration_outbox (type, operation, object_id, object_version)
					values ('AVAILABILITY', 'UPSERT', ?, 0)""", UUID.randomUUID());
		}
		AtomicInteger calls = new AtomicInteger();
		List<Integer> sizes = new ArrayList<>();
		AvailabilityProvider counting = productIds -> {
			calls.incrementAndGet();
			sizes.add(productIds.size());
			return this.availability.availability(productIds);
		};
		ChangeFeedService counted = new ChangeFeedService(this.sequencer, this.dataSource, this.transactionManager,
				counting, this.jsonMapper, this.feedProperties);

		ChangePage page = counted.read(0, 500, ChangeFilter.ALL);

		assertThat(page.items()).hasSize(500);
		assertThat(calls.get()).isEqualTo(1);
		assertThat(sizes).containsExactly(500);
		assertThat(page.items()).allSatisfy(event -> {
			assertThat(event.data().get("available").asInt()).isZero();
			assertThat(event.data().get("perWarehouse")).isEmpty();
		});
	}

	@Test
	void theServedValueFollowsTheContractSchema() {
		UUID product = stocked(this.store, 4);
		JsonNode value = served(product);
		List<String> fields = new ArrayList<>();
		value.propertyNames().forEach(fields::add);
		assertThat(fields).containsExactlyInAnyOrder("productId", "available", "perWarehouse", "computedAt");
		assertThat(value.get("productId").asString()).isEqualTo(product.toString());
		assertThat(value.get("available").isInt()).isTrue();
		assertThat(value.get("computedAt").isString()).isTrue();
		JsonNode store = value.get("perWarehouse").get(0);
		List<String> storeFields = new ArrayList<>();
		store.propertyNames().forEach(storeFields::add);
		assertThat(storeFields).containsExactlyInAnyOrder("warehouseId", "available");
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private <T> T assertOneEvent(UUID product, java.util.function.Supplier<T> step) {
		int before = availabilityEventsOf(product);
		T result = step.get();
		assertThat(availabilityEventsOf(product)).as("un événement AVAILABILITY par étape").isEqualTo(before + 1);
		return result;
	}

	private UUID stocked(UUID warehouse, int quantity) {
		UUID product = this.fixtures.product("monture", 15000);
		receive(warehouse, product, quantity);
		return product;
	}

	private void receive(UUID warehouse, UUID product, int quantity) {
		as(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(warehouse, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock initial")));
	}

	private SaleResponse sell(UUID warehouse, CreateSaleRequest.Item... items) {
		UUID seller = warehouse.equals(this.depot) ? this.depotSeller : this.actor;
		return as(seller, () -> this.sales.create(new CreateSaleRequest(null, null, null, PaymentMethod.CASH, null, null,
				List.of(items)), warehouse));
	}

	private static CreateSaleRequest.Item item(UUID product, int quantity) {
		return new CreateSaleRequest.Item(product, quantity, null);
	}

	private List<UUID> availabilityEvents() {
		return this.jdbc.queryForList("select object_id from integration_outbox where type = 'AVAILABILITY' order by write_id",
				UUID.class);
	}

	private int availabilityEventsOf(UUID product) {
		return this.jdbc.queryForObject(
				"select count(*) from integration_outbox where type = 'AVAILABILITY' and object_id = ?", Integer.class,
				product);
	}

	private int stockOf(UUID product) {
		return this.jdbc.queryForObject("""
				select coalesce(sum(l.quantity), 0) from stock_level l join warehouse w on w.id = l.warehouse_id
				where l.product_id = ? and w.active""", Integer.class, product);
	}

	/** Valeur servie par le flux pour le dernier événement AVAILABILITY du produit (toutes pages lues). */
	private JsonNode served(UUID product) {
		long after = 0;
		JsonNode last = null;
		Set<Long> seen = new HashSet<>();
		while (true) {
			ChangePage page = this.feed.read(after, 500, ChangeFilter.ALL);
			for (ChangeEvent event : page.items()) {
				if (event.type() == IntegrationEventType.AVAILABILITY && event.objectId().equals(product)
						&& seen.add(event.seq())) {
					last = event.data();
				}
			}
			after = page.lastSeq();
			if (!page.hasMore()) {
				break;
			}
		}
		assertThat(last).as("événement AVAILABILITY servi pour %s", product).isNotNull();
		return last;
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
