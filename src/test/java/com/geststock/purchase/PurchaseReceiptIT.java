package com.geststock.purchase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.common.security.Permissions;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.purchase.dto.CreatePurchaseOrderRequest;
import com.geststock.purchase.dto.ReceivePurchaseOrderRequest;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Réceptions en transactions réelles (validées par COMMIT, comme en production) :
 * concurrence, et garde-fous de la base qui rendent impossible une réception sans entrée en
 * stock — y compris par une écriture SQL directe.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class PurchaseReceiptIT {

	@Autowired
	private PurchaseOrderService orders;

	@Autowired
	private GoodsReceiptService receipts;

	@Autowired
	private StockService stock;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private UUID actor;

	private UUID warehouse;

	private UUID product;

	private UUID supplier;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("acheteur");
		this.warehouse = this.fixtures.warehouse("réception");
		this.product = this.fixtures.product("verre", 4000);
		this.supplier = this.jdbc.queryForObject("insert into supplier (code, name) values (?, ?) returning id",
				UUID.class, "FOU-IT-" + this.warehouse.toString().substring(0, 8).toUpperCase(),
				"Fournisseur IT " + this.warehouse);
	}

	@AfterEach
	void tearDown() {
		assertThat(receiptInvariantViolations()).as("quantité reçue = lignes reçues = entrées en stock").isEmpty();
		assertThat(this.fixtures.invariantViolations()).as("niveau = somme des mouvements").isEmpty();
		purgePurchases();
		this.fixtures.purge();
	}

	@Test
	void concurrentFullReceiptsOfTheSameOrderEnterStockOnce() throws Exception {
		UUID order = order(10);

		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			attempts.add(() -> {
				try {
					asBuyer(() -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, null, null, null), this.warehouse));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
					return false;
				}
			});
		}

		assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(this.fixtures.level(this.warehouse, this.product)).isEqualTo(10);
		assertThat(count("select count(*) from goods_receipt where purchase_order_id = ?", order)).isEqualTo(1);
		assertThat(status(order)).isEqualTo("RECEIVED");
	}

	@Test
	void concurrentPartialReceiptsNeverExceedTheOrderedQuantity() throws Exception {
		UUID order = order(10);

		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			attempts.add(() -> {
				try {
					asBuyer(() -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, null, null,
							List.of(new ReceivePurchaseOrderRequest.Line(this.product, 3))), this.warehouse));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isIn(ErrorCode.RECEIPT_QUANTITY_EXCEEDS_REMAINING,
							ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
					return false;
				}
			});
		}

		assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(3);
		assertThat(this.fixtures.level(this.warehouse, this.product)).isEqualTo(9);
		assertThat(this.jdbc.queryForObject(
				"select received_quantity from purchase_order_line where purchase_order_id = ?", Integer.class, order))
			.isEqualTo(9);
		assertThat(status(order)).isEqualTo("PARTIALLY_RECEIVED");
	}

	@Test
	void concurrentReceiptsOfDifferentOrdersForTheSameProductsAllSucceed() throws Exception {
		UUID frame = this.fixtures.product("monture", 15000);
		List<UUID> orders = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			// Ordre des lignes croisé d'une commande à l'autre : ni 409 sur le prix d'achat, ni interblocage.
			List<CreatePurchaseOrderRequest.Line> lines = i % 2 == 0
					? List.of(line(this.product, 2), line(frame, 1))
					: List.of(line(frame, 1), line(this.product, 2));
			orders.add(asBuyer(() -> this.orders.create(
					new CreatePurchaseOrderRequest(this.supplier, this.warehouse, null, null, null, lines), this.warehouse))
				.id());
		}

		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (UUID order : orders) {
			attempts.add(() -> {
				asBuyer(() -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, null, null, null), this.warehouse));
				return true;
			});
		}

		assertThat(runConcurrently(attempts)).hasSize(6).allMatch(Boolean::booleanValue);
		assertThat(this.fixtures.level(this.warehouse, this.product)).isEqualTo(12);
		assertThat(this.fixtures.level(this.warehouse, frame)).isEqualTo(6);
	}

	@Test
	void theDatabaseRefusesAReceivedQuantityThatDidNotEnterStock() {
		UUID order = order(5);

		// Reproduction du bug de l'audit : marquer la commande reçue sans mouvement de stock.
		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> {
			this.jdbc.update("update purchase_order_line set received_quantity = 5 where purchase_order_id = ?", order);
			this.jdbc.update("""
					update purchase_order set status = 'RECEIVED', received_amount = total_amount, last_receipt_at = now()
					where id = ?""", order);
		})).hasStackTraceContaining("réceptionnée(s) en stock");

		assertThat(this.jdbc.queryForObject(
				"select received_quantity from purchase_order_line where purchase_order_id = ?", Integer.class, order))
			.isZero();
		assertThat(status(order)).isEqualTo("ORDERED");
		assertThat(this.fixtures.level(this.warehouse, this.product)).isZero();
	}

	@Test
	void theDatabaseRefusesAReceiptLineWithoutItsOwnStockEntry() {
		UUID order = order(5);
		asBuyer(() -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, null, null,
				List.of(new ReceivePurchaseOrderRequest.Line(this.product, 2))), this.warehouse));
		UUID receipt = this.jdbc.queryForObject("select id from goods_receipt where purchase_order_id = ?", UUID.class,
				order);
		UUID orderLine = this.jdbc.queryForObject("select id from purchase_order_line where purchase_order_id = ?",
				UUID.class, order);
		// Une entrée de stock qui n'est pas celle de la réception (saisie manuelle).
		UUID manualEntry = asBuyer(() -> this.stock.applyMovement(StockMovementCommand
			.of(this.warehouse, this.product, StockMovementType.RECEIPT, 1, StockSourceType.MANUAL, "Saisie manuelle")
			.withUnitCost(new java.math.BigDecimal("3500")))).getId();

		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> this.jdbc.update("""
				insert into goods_receipt_line (goods_receipt_id, line_number, purchase_order_line_id, product_id,
				                                quantity, unit_cost, line_total, stock_movement_id)
				values (?, 99, ?, ?, 1, 3500, 3500, ?)""", receipt, orderLine, this.product, manualEntry)))
			.hasStackTraceContaining("aucune entrée en stock ne correspond");

		assertThat(count("select count(*) from goods_receipt_line where goods_receipt_id = ?", receipt)).isEqualTo(1);
	}

	@Test
	void receiptsAreAppendOnly() {
		UUID order = order(3);
		asBuyer(() -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, null, null, null), this.warehouse));

		assertThatThrownBy(() -> this.jdbc.update("update goods_receipt set total_quantity = 30 where purchase_order_id = ?",
				order)).hasStackTraceContaining("La table goods_receipt est en ajout seul");
		assertThatThrownBy(() -> this.jdbc.update("""
				delete from goods_receipt_line
				where goods_receipt_id in (select id from goods_receipt where purchase_order_id = ?)""", order))
			.hasStackTraceContaining("La table goods_receipt_line est en ajout seul");
		assertThat(this.fixtures.level(this.warehouse, this.product)).isEqualTo(3);
	}

	@Test
	void aCommittedReceiptSatisfiesTheDeferredConstraints() {
		UUID order = order(4);

		asBuyer(() -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, "BL-42", null,
				List.of(new ReceivePurchaseOrderRequest.Line(this.product, 1))), this.warehouse));
		asBuyer(() -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, null, null, null), this.warehouse));

		assertThat(this.fixtures.level(this.warehouse, this.product)).isEqualTo(4);
		assertThat(this.fixtures.movementCount(this.warehouse, this.product)).isEqualTo(2);
		assertThat(status(order)).isEqualTo("RECEIVED");
		assertThat(this.jdbc.queryForObject("select purchase_price from product where id = ?", Integer.class,
				this.product)).isEqualTo(3500);
	}

	// --- Outils ----------------------------------------------------------------------------------

	private static CreatePurchaseOrderRequest.Line line(UUID productId, int quantity) {
		return new CreatePurchaseOrderRequest.Line(productId, quantity, new java.math.BigDecimal("3500"));
	}

	/** Commande de {@code quantity} produits à 3 500 (le prix de référence est 4 000), validée. */
	private UUID order(int quantity) {
		return asBuyer(() -> this.orders.create(new CreatePurchaseOrderRequest(this.supplier, this.warehouse, null, null,
				null, List.of(new CreatePurchaseOrderRequest.Line(this.product, quantity, new java.math.BigDecimal("3500")))),
				this.warehouse)).id();
	}

	private String status(UUID order) {
		return this.jdbc.queryForObject("select status from purchase_order where id = ?", String.class, order);
	}

	private int count(String sql, UUID id) {
		return this.jdbc.queryForObject(sql, Integer.class, id);
	}

	/**
	 * Pour chaque ligne de commande des tests : quantité reçue = somme des lignes reçues = somme
	 * des mouvements RECEIPT produits par ses réceptions.
	 */
	private List<String> receiptInvariantViolations() {
		return this.jdbc.queryForList("""
				select ol.id || ' : reçue ' || ol.received_quantity || ', lignes ' || coalesce(r.total, 0)
				       || ', stock ' || coalesce(m.total, 0)
				from purchase_order_line ol
				join purchase_order o on o.id = ol.purchase_order_id
				left join (select purchase_order_line_id, sum(quantity) as total from goods_receipt_line
				           group by purchase_order_line_id) r on r.purchase_order_line_id = ol.id
				left join (select grl.purchase_order_line_id, sum(sm.quantity) as total
				           from goods_receipt_line grl join stock_movement sm on sm.id = grl.stock_movement_id
				           where sm.type = 'RECEIPT' and sm.source_type = 'PURCHASE_ORDER'
				             and sm.source_id = grl.goods_receipt_id
				           group by grl.purchase_order_line_id) m on m.purchase_order_line_id = ol.id
				where o.warehouse_id = ?
				  and (ol.received_quantity <> coalesce(r.total, 0) or ol.received_quantity <> coalesce(m.total, 0))""",
				String.class, this.warehouse);
	}

	/** Supprime les pièces d'achat des tests (journal en ajout seul : triggers suspendus). */
	private void purgePurchases() {
		this.transactions.executeWithoutResult(status -> {
			this.jdbc.execute("alter table goods_receipt_line disable trigger user");
			this.jdbc.execute("alter table goods_receipt disable trigger user");
			this.jdbc.update("""
					delete from goods_receipt_line
					where goods_receipt_id in (select id from goods_receipt where warehouse_id = ?)""", this.warehouse);
			this.jdbc.update("delete from goods_receipt where warehouse_id = ?", this.warehouse);
			this.jdbc.execute("alter table goods_receipt enable trigger user");
			this.jdbc.execute("alter table goods_receipt_line enable trigger user");
			this.jdbc.update("""
					delete from purchase_order_line
					where purchase_order_id in (select id from purchase_order where warehouse_id = ?)""", this.warehouse);
			this.jdbc.update("delete from purchase_order where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from supplier where id = ?", this.supplier);
		});
	}

	/** Exécute l'action au nom de l'acheteur, avec toutes les permissions (dont warehouse:all). */
	private <T> T asBuyer(Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		try {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(StockFixtures.authentication(this.actor, Permissions.ALL.toArray(String[]::new)));
			SecurityContextHolder.setContext(context);
			return action.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

	/** Lance toutes les tâches au même instant et attend leurs résultats. */
	private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 8));
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
