package com.geststock.returns;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.common.security.Permissions;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.returns.dto.CreateCustomerReturnRequest;
import com.geststock.returns.dto.CustomerReturnResponse;
import com.geststock.sale.SaleService;
import com.geststock.sale.dto.CreateSaleRequest;
import com.geststock.sale.dto.SaleResponse;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retours en transactions réelles (COMMIT / ROLLBACK) : atomicité quand le règlement est refusé
 * après la remise en stock, retours concurrents sur une même vente, et garde-fous de la base
 * (retour sans règlement, au-delà du vendu, modifié après coup, vente annulée après retour).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CustomerReturnTransactionIT {

	@Autowired
	private CustomerReturnService returns;

	@Autowired
	private SaleService sales;

	@Autowired
	private StockService stock;

	@Autowired
	private CashService cash;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private UUID actor;

	private UUID warehouse;

	private UUID frame;

	private UUID customer;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("retours");
		this.warehouse = this.fixtures.warehouse("retours");
		this.frame = this.fixtures.product("monture-retour", 15000); // prix de vente 30 000
		this.customer = this.jdbc.queryForObject(
				"insert into customer (code, name, credit_limit) values (?, 'Client retours IT', 100000) returning id",
				UUID.class, "CLI-RT-" + this.warehouse.toString().substring(0, 8).toUpperCase());
		asSeller(() -> this.stock.applyMovement(StockMovementCommand.of(this.warehouse, this.frame,
				StockMovementType.RECEIPT, 10, StockSourceType.MANUAL, "Stock initial")));
		asSeller(() -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("100000"), "Caisse IT", null),
				this.warehouse));
	}

	@AfterEach
	void tearDown() {
		assertThat(this.fixtures.invariantViolations()).as("niveau = somme des mouvements").isEmpty();
		purge();
		this.fixtures.purge();
	}

	// --- Atomicité ----------------------------------------------------------------------------------

	@Test
	void anAccountCreditBeyondTheDebtRollsBackTheRestock() {
		// Vente payée comptant par un client : aucune dette sur laquelle imputer un avoir.
		SaleResponse sale = sell(this.customer, null, 2);

		assertThatThrownBy(() -> giveBack(sale.id(), ReturnSettlement.ACCOUNT_CREDIT, 1, ItemCondition.RESELLABLE))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.AMOUNT_EXCEEDS_CUSTOMER_BALANCE);

		assertThat(this.fixtures.level(this.warehouse, this.frame)).as("remise en stock annulée").isEqualTo(8);
		assertThat(returnCount()).isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_log where entity_type = 'CUSTOMER_RETURN' "
				+ "and warehouse_id = ?", Integer.class, this.warehouse)).isZero();
		// La vente est intacte.
		assertThat(asSeller(() -> this.sales.get(sale.id())).status().name()).isEqualTo("COMPLETED");
	}

	@Test
	void aCommittedReturnCarriesItsRestockAndItsRefund() {
		SaleResponse sale = sell(null, null, 3);

		CustomerReturnResponse created = giveBack(sale.id(), ReturnSettlement.REFUND, 2, ItemCondition.RESELLABLE);

		assertThat(created.totalAmount()).isEqualByComparingTo("60000");
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(9);
		assertThat(this.jdbc.queryForObject("""
				select amount from cash_entry where entry_type = 'RETURN_REFUND' and source_id = ?""", BigDecimal.class,
				created.id())).isEqualByComparingTo("60000");
		assertThatThrownBy(() -> asSeller(
				() -> this.sales.cancel(sale.id(), new com.geststock.sale.dto.CancelSaleRequest("Tout annuler"), this.warehouse)))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SALE_HAS_RETURNS);
	}

	// --- Concurrence ----------------------------------------------------------------------------------

	@Test
	void concurrentReturnsNeverExceedTheSoldQuantity() throws Exception {
		SaleResponse sale = sell(null, null, 3);

		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			attempts.add(() -> {
				try {
					giveBack(sale.id(), ReturnSettlement.REFUND, 1, ItemCondition.RESELLABLE);
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.RETURN_QUANTITY_EXCEEDS_SOLD);
					return false;
				}
			});
		}

		assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(3);
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(10);
		assertThat(this.jdbc.queryForObject("""
				select coalesce(sum(amount), 0) from cash_entry
				where entry_type = 'RETURN_REFUND' and source_id in (select id from customer_return where sale_id = ?)""",
				BigDecimal.class, sale.id())).as("remboursé = vendu, jamais plus").isEqualByComparingTo("90000");
		assertThat(this.jdbc.queryForObject("select count(distinct number) from customer_return where sale_id = ?",
				Integer.class, sale.id())).isEqualTo(3);
	}

	// --- Garde-fous de la base -------------------------------------------------------------------------

	@Test
	void theDatabaseRefusesARefundThatNeverLeftTheDrawer() {
		SaleResponse sale = sell(null, null, 1);
		Map<String, Object> line = saleLine(sale.id());

		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> {
			UUID id = insertReturn(sale.id(), "RET-IT-SANS-CAISSE", "REFUND", "CASH", null, 30000);
			insertLine(id, line, 1, 30000, line.get("unit_cost"));
		})).hasStackTraceContaining("remboursement absent de la caisse");
		assertThat(returnCount()).isZero();
	}

	@Test
	void theDatabaseRefusesReturningMoreThanSold() {
		SaleResponse sale = sell(this.customer, BigDecimal.ZERO, 1);
		Map<String, Object> line = saleLine(sale.id());

		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> {
			UUID id = insertReturn(sale.id(), "RET-IT-TROP", "ACCOUNT_CREDIT", null, this.customer, 60000);
			insertLine(id, line, 2, 60000, line.get("unit_cost"));
		})).hasStackTraceContaining("quantité retournée supérieure à la quantité vendue");
		// Une ligne qui ne reprend pas le coût de la vente est refusée immédiatement.
		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> {
			UUID id = insertReturn(sale.id(), "RET-IT-COUT", "ACCOUNT_CREDIT", null, this.customer, 30000);
			insertLine(id, line, 1, 30000, new BigDecimal("1"));
		})).hasStackTraceContaining("la ligne ne correspond pas à la vente retournée");
	}

	@Test
	void theDatabaseKeepsAReturnInTheWarehouseOfItsSale() {
		SaleResponse sale = sell(this.customer, BigDecimal.ZERO, 1);
		UUID otherWarehouse = this.fixtures.warehouse("retours-ailleurs");

		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> this.jdbc.update("""
				insert into customer_return (number, sale_id, warehouse_id, customer_id, settlement, total_amount,
				                             reason, created_at, created_by)
				values ('RET-IT-AILLEURS', ?, ?, ?, 'ACCOUNT_CREDIT', 30000, 'SQL direct', now(), ?)""", sale.id(),
				otherWarehouse, this.customer, this.actor)))
			.hasStackTraceContaining("le retour doit être dans le dépôt de sa vente");
		assertThat(this.jdbc.queryForObject("select count(*) from customer_return where sale_id = ?", Integer.class,
				sale.id())).isZero();
	}

	@Test
	void theDatabaseFreezesAReturnAndItsSale() {
		SaleResponse sale = sell(null, null, 2);
		CustomerReturnResponse created = giveBack(sale.id(), ReturnSettlement.REFUND, 1, ItemCondition.DEFECTIVE);

		assertThatThrownBy(() -> this.jdbc.update("update customer_return set total_amount = 1 where id = ?",
				created.id()))
			.hasStackTraceContaining("La table customer_return est en ajout seul");
		assertThatThrownBy(() -> this.jdbc.update("delete from customer_return_line where return_id = ?", created.id()))
			.hasStackTraceContaining("La table customer_return_line est en ajout seul");
		// Annuler « à la main » une vente ayant des retours : refusé au COMMIT (premier garde-fou
		// différé atteint : remise en stock, remboursement ou retours).
		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> this.jdbc.update(
				"update sale set status = 'CANCELLED', cancelled_at = now() where id = ?", sale.id())))
			.hasStackTraceContaining("annulée");
		assertThat(asSeller(() -> this.sales.get(sale.id())).status().name()).isEqualTo("COMPLETED");
	}

	// --- Outils ------------------------------------------------------------------------------------------

	private SaleResponse sell(UUID customerId, BigDecimal paidAmount, int quantity) {
		return asSeller(() -> this.sales.create(new CreateSaleRequest(null, customerId, null, PaymentMethod.CASH,
				paidAmount, null, List.of(new CreateSaleRequest.Item(this.frame, quantity, null))), this.warehouse));
	}

	private CustomerReturnResponse giveBack(UUID saleId, ReturnSettlement settlement, int quantity,
			ItemCondition condition) {
		return asSeller(() -> this.returns.create(saleId, new CreateCustomerReturnRequest(settlement, null, "Retour IT",
				List.of(new CreateCustomerReturnRequest.Item(this.frame, quantity, condition))), this.warehouse));
	}

	private Map<String, Object> saleLine(UUID saleId) {
		return this.jdbc.queryForMap(
				"select id, product_id, product_sku, product_name, unit_cost from sale_line where sale_id = ?", saleId);
	}

	private UUID insertReturn(UUID saleId, String number, String settlement, String method, UUID customerId,
			int total) {
		return this.jdbc.queryForObject("""
				insert into customer_return (number, sale_id, warehouse_id, customer_id, settlement, refund_method,
				                             total_amount, reason, created_at, created_by)
				values (?, ?, ?, ?, ?, ?, ?, 'SQL direct', now(), ?) returning id""", UUID.class, number, saleId,
				this.warehouse, customerId, settlement, method, total, this.actor);
	}

	private void insertLine(UUID returnId, Map<String, Object> line, int quantity, int amount, Object unitCost) {
		this.jdbc.update("""
				insert into customer_return_line (return_id, line_number, sale_line_id, product_id, product_sku,
				                                  product_name, quantity, amount, unit_cost, item_condition)
				values (?, 1, ?, ?, ?, ?, ?, ?, ?, 'DEFECTIVE')""", returnId, line.get("id"), line.get("product_id"),
				line.get("product_sku"), line.get("product_name"), quantity, amount, unitCost);
	}

	private int returnCount() {
		return this.jdbc.queryForObject("select count(*) from customer_return where warehouse_id = ?", Integer.class,
				this.warehouse);
	}

	/** Supprime retours, ventes, caisse, audit et compte client (tables en ajout seul : triggers suspendus). */
	private void purge() {
		List<String> tables = List.of("customer_return_line", "customer_return", "sale_line", "sale", "audit_log",
				"customer_account_entry", "cash_entry", "cash_session");
		this.transactions.executeWithoutResult(status -> {
			tables.forEach(table -> this.jdbc.execute("alter table " + table + " disable trigger user"));
			this.jdbc.update("""
					delete from customer_return_line
					where return_id in (select id from customer_return where warehouse_id = ?)""", this.warehouse);
			this.jdbc.update("delete from customer_return where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from cash_entry where session_id in (select id from cash_session where warehouse_id = ?)",
					this.warehouse);
			this.jdbc.update("delete from cash_session where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from sale_line where sale_id in (select id from sale where warehouse_id = ?)",
					this.warehouse);
			this.jdbc.update("delete from sale where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from audit_log where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from customer_account_entry where customer_id = ?", this.customer);
			tables.forEach(table -> this.jdbc.execute("alter table " + table + " enable trigger user"));
			this.jdbc.update("delete from customer where id = ?", this.customer);
		});
	}

	private <T> T asSeller(Supplier<T> action) {
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
