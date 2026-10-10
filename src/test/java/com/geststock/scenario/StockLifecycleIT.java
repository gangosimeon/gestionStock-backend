package com.geststock.scenario;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vie complète d'un produit sur deux dépôts, en transactions réelles, à travers tous les modules
 * qui touchent le stock : réception (partielle puis solde), transfert, vente, retour, annulation,
 * perte, refus pour stock insuffisant, inventaire. À chaque étape, le niveau de chaque dépôt est
 * celui attendu ; à la fin, chaque niveau est exactement la somme de ses mouvements, et chaque
 * mouvement de pièce désigne sa pièce.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class StockLifecycleIT {

	@Autowired
	private PurchaseOrderService orders;

	@Autowired
	private GoodsReceiptService receipts;

	@Autowired
	private StockService stock;

	@Autowired
	private SaleService sales;

	@Autowired
	private CustomerReturnService returns;

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
		this.actor = this.fixtures.user("cycle");
		this.store = this.fixtures.warehouse("magasin");
		this.depot = this.fixtures.warehouse("depot");
		this.frame = this.fixtures.product("monture", 15000);
		this.supplier = this.jdbc.queryForObject("insert into supplier (code, name) values (?, 'Fournisseur cycle') "
				+ "returning id", UUID.class, "FOU-" + this.store.toString().substring(0, 8).toUpperCase());
	}

	@AfterEach
	void tearDown() {
		this.scenario.purge(List.of(this.store, this.depot), List.of(), List.of(this.supplier));
		this.fixtures.purge();
	}

	@Test
	void aProductLifeAcrossTwoWarehousesKeepsEveryLevelEqualToItsMovements() {
		// Réception en deux fois au magasin : 6 puis le reste (4), au coût négocié de 16 000.
		UUID order = as(this.actor, () -> this.orders.create(new CreatePurchaseOrderRequest(this.supplier, null, null,
				null, null, List.of(new CreatePurchaseOrderRequest.Line(this.frame, 10, new BigDecimal("16000")))),
				this.store).id());
		as(this.actor, () -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, "BL-1", null,
				List.of(new ReceivePurchaseOrderRequest.Line(this.frame, 6)))));
		assertLevels(6, 0);
		as(this.actor, () -> this.receipts.receive(order, new ReceivePurchaseOrderRequest(null, "BL-2", null, null)));
		assertLevels(10, 0);
		assertThat(this.jdbc.queryForObject("select purchase_price from product where id = ?", BigDecimal.class,
				this.frame)).as("dernier coût reçu = prix d'achat de référence").isEqualByComparingTo("16000");

		// Transfert de 3 vers le dépôt.
		as(this.actor, () -> this.stock.transfer(this.store, this.depot, this.frame, 3, "Réassort"));
		assertLevels(7, 3);

		// Ventes au dépôt (caisse ouverte au dépôt), retour d'un article revendable, annulation.
		as(this.actor, () -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("50000"), null, null), this.depot));
		SaleResponse sale = sell(this.depot, 2);
		assertLevels(7, 1);
		as(this.actor, () -> this.returns.create(sale.id(), new CreateCustomerReturnRequest(ReturnSettlement.REFUND, null,
				"Taille", List.of(new CreateCustomerReturnRequest.Item(this.frame, 1, ItemCondition.RESELLABLE)))));
		assertLevels(7, 2);
		SaleResponse mistaken = sell(this.depot, 1);
		assertLevels(7, 1);
		as(this.actor, () -> this.sales.cancel(mistaken.id(), new CancelSaleRequest("Erreur de saisie")));
		assertLevels(7, 2);

		// Stock insuffisant au dépôt (2 disponibles) : refus, rien d'écrit — le magasin n'est pas puisé.
		long salesBefore = count("select count(*) from sale where warehouse_id = ?", this.depot);
		assertThat(refusal(() -> sell(this.depot, 3))).isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
		assertLevels(7, 2);
		assertThat(count("select count(*) from sale where warehouse_id = ?", this.depot)).isEqualTo(salesBefore);

		// Perte au magasin, puis inventaire : 5 comptés pour 6 attendus.
		as(this.actor, () -> this.stock.applyManualMovement(StockMovementCommand.of(this.store, this.frame,
				StockMovementType.LOSS, -1, StockSourceType.MANUAL, "Casse en vitrine")));
		assertLevels(6, 2);
		UUID inventory = as(this.actor, () -> this.inventories.create(new CreateInventoryRequest(null,
				InventoryScope.SELECTION, null, List.of(this.frame), null), this.store).id());
		as(this.actor, () -> this.inventories.start(inventory));
		as(this.actor, () -> this.inventories.recordCounts(inventory,
				List.of(new InventoryCountsRequest.Count(this.frame, 5, null))));
		as(this.actor, () -> this.inventories.submit(inventory));
		as(this.actor, () -> this.inventories.validate(inventory, "Écart constaté"));
		assertLevels(5, 2);

		// Chaque niveau = somme de ses mouvements = solde du dernier mouvement.
		assertThat(this.fixtures.invariantViolations()).isEmpty();
		assertThat(types(this.store)).containsExactly("RECEIPT", "RECEIPT", "TRANSFER_OUT", "LOSS", "INVENTORY");
		assertThat(types(this.depot))
			.containsExactly("TRANSFER_IN", "SALE", "CUSTOMER_RETURN", "SALE", "SALE_CANCELLATION");
		// Mouvements de pièce : source renseignée ; ventes et retours au coût de la vente (16 000).
		assertThat(count("""
				select count(*) from stock_movement where warehouse_id = any(?) and source_type <> 'MANUAL'
				  and source_id is null""", (Object) new UUID[] { this.store, this.depot })).isZero();
		assertThat(this.jdbc.queryForList("""
				select distinct unit_cost from stock_movement where warehouse_id = ? and type in ('SALE', 'CUSTOMER_RETURN')""",
				BigDecimal.class, this.depot)).singleElement().satisfies(cost -> assertThat(cost).isEqualByComparingTo("16000"));
		// Le transfert sort et entre sous le même identifiant de corrélation.
		assertThat(count("""
				select count(distinct correlation_id) from stock_movement
				where type in ('TRANSFER_OUT', 'TRANSFER_IN') and warehouse_id = any(?)""",
				(Object) new UUID[] { this.store, this.depot })).isEqualTo(1);
	}

	// --- Outils ------------------------------------------------------------------------------------

	private SaleResponse sell(UUID warehouseId, int quantity) {
		return as(this.actor, () -> this.sales.create(new CreateSaleRequest(null, null, null, PaymentMethod.CASH, null,
				null, List.of(new CreateSaleRequest.Item(this.frame, quantity, null))), warehouseId));
	}

	private void assertLevels(int store, int depot) {
		assertThat(Map.of("magasin", this.fixtures.level(this.store, this.frame), "dépôt",
				this.fixtures.level(this.depot, this.frame)))
			.containsEntry("magasin", store)
			.containsEntry("dépôt", depot);
	}

	private List<String> types(UUID warehouseId) {
		return this.jdbc.queryForList("""
				select type from stock_movement where warehouse_id = ? and product_id = ? order by created_at, id""",
				String.class, warehouseId, this.frame);
	}

	private long count(String sql, Object... args) {
		Long value = this.jdbc.queryForObject(sql, Long.class, args);
		return value != null ? value : 0;
	}

}
