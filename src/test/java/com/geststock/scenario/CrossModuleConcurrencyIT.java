package com.geststock.scenario;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

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
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.BusinessScenario;
import com.geststock.support.PostgresTestConfiguration;

import static com.geststock.support.BusinessScenario.as;
import static com.geststock.support.BusinessScenario.refusal;
import static com.geststock.support.BusinessScenario.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrence <b>entre modules</b> sur le même stock, en transactions réelles. Chaque module sait
 * déjà ne pas survendre seul (tests de module) ; ici des ventes, transferts, pertes, réceptions,
 * retours, annulations et validations d'inventaire se disputent les mêmes niveaux au même instant.
 * Règles vérifiées : jamais de stock négatif, jamais de mouvement perdu ou compté deux fois,
 * refus uniquement pour une raison métier, niveaux = somme des mouvements.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CrossModuleConcurrencyIT {

	@Autowired
	private SaleService sales;

	@Autowired
	private CustomerReturnService returns;

	@Autowired
	private StockService stock;

	@Autowired
	private PurchaseOrderService orders;

	@Autowired
	private GoodsReceiptService receipts;

	@Autowired
	private InventoryService inventories;

	@Autowired
	private CashService cash;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private BusinessScenario scenario;

	private UUID actor;

	private UUID store;

	private UUID depot;

	private UUID frame;

	private UUID supplier;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.scenario = new BusinessScenario(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("concurrence");
		this.store = this.fixtures.warehouse("magasin");
		this.depot = this.fixtures.warehouse("depot");
		this.frame = this.fixtures.product("monture", 15000);
		this.supplier = this.jdbc.queryForObject("insert into supplier (code, name) values (?, 'Fournisseur') returning id",
				UUID.class, "FOU-" + this.store.toString().substring(0, 8).toUpperCase());
		as(this.actor, () -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("1000000"), null, null), this.store));
	}

	@AfterEach
	void tearDown() {
		assertThat(this.fixtures.invariantViolations()).as("niveau = somme des mouvements").isEmpty();
		this.scenario.purge(List.of(this.store, this.depot), List.of(), List.of(this.supplier));
		this.fixtures.purge();
	}

	@Test
	void salesTransfersAndLossesCompetingForTheSameStockNeverOversell() throws Exception {
		receive(this.store, 10);
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			tasks.add(() -> outcome("vente", () -> sell(1)));
			tasks.add(() -> outcome("transfert",
					() -> as(this.actor, () -> this.stock.transfer(this.store, this.depot, this.frame, 1, null))));
			tasks.add(() -> outcome("perte", () -> as(this.actor, () -> this.stock.applyManualMovement(StockMovementCommand
				.of(this.store, this.frame, StockMovementType.LOSS, -1, StockSourceType.MANUAL, "Casse")))));
		}

		List<String> outcomes = runConcurrently(tasks);

		assertThat(outcomes).as("seul refus admis : stock insuffisant")
			.allMatch(o -> !o.contains(":") || o.endsWith(":" + ErrorCode.STOCK_INSUFFICIENT));
		long succeeded = outcomes.stream().filter(o -> !o.contains(":")).count();
		long transfers = outcomes.stream().filter("transfert"::equals).count();
		long sold = outcomes.stream().filter("vente"::equals).count();
		assertThat(succeeded).as("exactement les 10 unités disponibles").isEqualTo(10);
		assertThat(this.fixtures.level(this.store, this.frame)).isZero();
		assertThat(this.fixtures.level(this.depot, this.frame)).as("arrivées au dépôt").isEqualTo((int) transfers);
		assertThat(count("select count(*) from sale where warehouse_id = ? and status = 'COMPLETED'", this.store))
			.isEqualTo(sold);
	}

	@Test
	void aCancellationAndAReturnOfTheSameSaleExcludeEachOther() throws Exception {
		receive(this.store, 20);
		List<SaleResponse> sold = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			sold.add(sell(2));
		}
		List<Callable<String>> tasks = new ArrayList<>();
		for (SaleResponse sale : sold) {
			tasks.add(() -> outcome("annulation",
					() -> as(this.actor, () -> this.sales.cancel(sale.id(), new CancelSaleRequest("Doublon"), this.store))));
			tasks.add(() -> outcome("retour", () -> as(this.actor, () -> this.returns.create(sale.id(),
					new CreateCustomerReturnRequest(ReturnSettlement.REFUND, null, "Rendu",
							List.of(new CreateCustomerReturnRequest.Item(this.frame, 1, ItemCondition.RESELLABLE))), this.store))));
		}

		List<String> outcomes = runConcurrently(tasks);

		int expectedStock = 20 - 12;
		for (int i = 0; i < sold.size(); i++) {
			String cancellation = outcomes.get(2 * i);
			String giveBack = outcomes.get(2 * i + 1);
			String status = this.jdbc.queryForObject("select status from sale where id = ?", String.class,
					sold.get(i).id());
			long returnCount = count("select count(*) from customer_return where sale_id = ?", sold.get(i).id());
			if ("annulation".equals(cancellation)) {
				// L'annulation a gagné : la vente ne peut plus être retournée.
				assertThat(giveBack).isEqualTo("retour:" + ErrorCode.SALE_NOT_RETURNABLE);
				assertThat(status).isEqualTo("CANCELLED");
				assertThat(returnCount).isZero();
				expectedStock += 2;
			}
			else {
				// Le retour a gagné : la vente, qui a des retours, ne peut plus être annulée.
				assertThat(cancellation).isEqualTo("annulation:" + ErrorCode.SALE_HAS_RETURNS);
				assertThat(giveBack).isEqualTo("retour");
				assertThat(status).isEqualTo("COMPLETED");
				assertThat(returnCount).isEqualTo(1);
				expectedStock += 1;
			}
		}
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(expectedStock);
	}

	@Test
	void receiptsAndSalesOfTheSameProductInterleaveWithoutLosingAMovement() throws Exception {
		receive(this.store, 5);
		UUID order = as(this.actor, () -> this.orders.create(new CreatePurchaseOrderRequest(this.supplier, null, null,
				null, null, List.of(new CreatePurchaseOrderRequest.Line(this.frame, 10, new BigDecimal("15000")))),
				this.store).id());
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> outcome("réception", () -> as(this.actor, () -> this.receipts.receive(order,
					new ReceivePurchaseOrderRequest(null, null, null,
							List.of(new ReceivePurchaseOrderRequest.Line(this.frame, 1))), this.store))));
		}
		for (int i = 0; i < 12; i++) {
			tasks.add(() -> outcome("vente", () -> sell(1)));
		}

		List<String> outcomes = runConcurrently(tasks);

		assertThat(outcomes.stream().filter("réception"::equals).count()).as("chaque réception d'une unité passe")
			.isEqualTo(10);
		assertThat(outcomes).allMatch(o -> !o.contains(":") || o.equals("vente:" + ErrorCode.STOCK_INSUFFICIENT));
		long sold = outcomes.stream().filter("vente"::equals).count();
		assertThat(sold).isBetween(5L, 12L);
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(5 + 10 - (int) sold);
		assertThat(this.jdbc.queryForObject("select status from purchase_order where id = ?", String.class, order))
			.isEqualTo("RECEIVED");
	}

	@Test
	void anInventoryValidationRacingSalesAppliesTheMeasuredGapOrNothing() throws Exception {
		receive(this.store, 5);
		// Compté 2 pour 5 : écart mesuré −3, à appliquer tel quel à la validation.
		UUID inventory = as(this.actor, () -> this.inventories.create(new CreateInventoryRequest(null,
				InventoryScope.SELECTION, null, List.of(this.frame), null), this.store).id());
		as(this.actor, () -> this.inventories.start(inventory, this.store));
		as(this.actor, () -> this.inventories.recordCounts(inventory,
				List.of(new InventoryCountsRequest.Count(this.frame, 2, null)), this.store));
		as(this.actor, () -> this.inventories.submit(inventory, this.store));

		List<Callable<String>> tasks = new ArrayList<>();
		tasks.add(() -> outcome("validation", () -> as(this.actor, () -> this.inventories.validate(inventory, null, this.store))));
		for (int i = 0; i < 4; i++) {
			tasks.add(() -> outcome("vente", () -> sell(1)));
		}

		List<String> outcomes = runConcurrently(tasks);

		long sold = outcomes.stream().filter("vente"::equals).count();
		boolean validated = "validation".equals(outcomes.get(0));
		assertThat(outcomes).allMatch(o -> !o.contains(":") || o.endsWith(":" + ErrorCode.STOCK_INSUFFICIENT));
		String status = this.jdbc.queryForObject("select status from inventory_session where id = ?", String.class,
				inventory);
		assertThat(status).isEqualTo(validated ? "COMPLETED" : "VALIDATION");
		int level = this.fixtures.level(this.store, this.frame);
		assertThat(level).as("jamais négatif").isGreaterThanOrEqualTo(0);
		assertThat(level).isEqualTo(5 - (int) sold - (validated ? 3 : 0));
		assertThat(count("select count(*) from stock_movement where source_id = ? and type = 'INVENTORY'", inventory))
			.as("écart appliqué une fois ou pas du tout").isEqualTo(validated ? 1 : 0);
	}

	// --- Outils ------------------------------------------------------------------------------------

	private void receive(UUID warehouseId, int quantity) {
		as(this.actor, () -> this.stock.applyManualMovement(StockMovementCommand.of(warehouseId, this.frame,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock initial")));
	}

	private SaleResponse sell(int quantity) {
		return as(this.actor, () -> this.sales.create(new CreateSaleRequest(null, null, null, PaymentMethod.CASH, null,
				null, List.of(new CreateSaleRequest.Item(this.frame, quantity, null))), this.store));
	}

	/** {@code label} si l'action réussit, {@code label:CODE} si elle est refusée pour une raison métier. */
	private static String outcome(String label, Runnable action) {
		ErrorCode refused = refusal(action);
		return refused == null ? label : label + ":" + refused;
	}

	private long count(String sql, Object... args) {
		Long value = this.jdbc.queryForObject(sql, Long.class, args);
		return value != null ? value : 0;
	}

}
