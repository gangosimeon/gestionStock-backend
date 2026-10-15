package com.geststock.sale;

import java.math.BigDecimal;
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

import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.common.security.Permissions;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.sale.dto.CancelSaleRequest;
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
 * Ventes en transactions réelles (COMMIT / ROLLBACK, comme en production) : atomicité quand un
 * refus survient après les premières écritures, concurrence, et garde-fous de la base contre
 * une vente sans sortie de stock, sans créance ou modifiée après coup.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class SaleTransactionIT {

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

	private UUID lens;

	private UUID customer;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("vendeur");
		this.warehouse = this.fixtures.warehouse("vente");
		this.frame = this.fixtures.product("monture", 15000); // prix de vente 30 000
		this.lens = this.fixtures.product("verre", 4000); // prix de vente 8 000
		this.customer = this.jdbc.queryForObject(
				"insert into customer (code, name, credit_limit) values (?, 'Client vente IT', 50000) returning id", UUID.class,
				"CLI-VT-" + this.warehouse.toString().substring(0, 8).toUpperCase());
		receive(this.frame, 10);
		receive(this.lens, 5);
		asSeller(() -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("100000"), "Caisse IT", null),
				this.warehouse));
	}

	@AfterEach
	void tearDown() {
		assertThat(this.fixtures.invariantViolations()).as("niveau = somme des mouvements").isEmpty();
		purgeSales();
		this.fixtures.purge();
	}

	// --- Atomicité ----------------------------------------------------------------------------------

	@Test
	void aStockRefusalOnALaterLineLeavesNoTrace() {
		assertThatThrownBy(() -> sell(null, null, item(this.frame, 2), item(this.lens, 6)))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.STOCK_INSUFFICIENT);

		assertThat(this.fixtures.level(this.warehouse, this.frame)).as("première ligne annulée aussi").isEqualTo(10);
		assertThat(this.fixtures.movementCount(this.warehouse, this.frame)).isEqualTo(1);
		assertThat(saleCount()).isZero();
		assertThat(auditCount()).isZero();
	}

	@Test
	void aCustomerEnteredAtTheTillIsNotCreatedWhenTheSaleIsRefused() {
		String name = "Client Fantôme " + UUID.randomUUID();
		CreateSaleRequest request = new CreateSaleRequest(null, null, null, PaymentMethod.CASH, null, null,
				List.of(item(this.frame, 999)), new CreateSaleRequest.NewCustomer(name, "0700000000"));

		assertThatThrownBy(() -> asSeller(() -> this.sales.create(request, this.warehouse)))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.STOCK_INSUFFICIENT);

		assertThat(this.jdbc.queryForObject("select count(*) from customer where name = ?", Integer.class, name))
			.as("vente et client : tout ou rien").isZero();
		assertThat(this.jdbc.queryForObject("select count(*) from audit_log where entity_label like ?", Integer.class,
				"%" + name)).as("aucune trace de création").isZero();
		assertThat(saleCount()).isZero();
	}

	@Test
	void aCreditLimitRefusalRollsBackTheStockAlreadyTaken() {
		// 2 montures = 60 000 à crédit pour un plafond de 50 000 : refus après les sorties de stock.
		assertThatThrownBy(() -> sell(this.customer, BigDecimal.ZERO, item(this.frame, 2)))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED);

		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(10);
		assertThat(saleCount()).isZero();
		assertThat(balance()).isZero();
	}

	@Test
	void aSettledCreditBlocksTheCancellationWithoutRestocking() {
		SaleResponse sale = sell(this.customer, BigDecimal.ZERO, item(this.frame, 1));
		asSeller(() -> this.jdbc.update("""
				insert into customer_account_entry (customer_id, entry_type, direction, amount, balance_after, source_type,
				                                    source_id, created_by, warehouse_id)
				values (?, 'CREDIT_NOTE', 'CREDIT', 20000, 10000, 'CUSTOMER_RETURN', ?, ?, ?)""", this.customer,
				UUID.randomUUID(), this.actor, this.warehouse));

		assertThatThrownBy(() -> asSeller(() -> this.sales.cancel(sale.id(), new CancelSaleRequest("Retour"), this.warehouse)))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SALE_CREDIT_ALREADY_SETTLED);

		assertThat(this.fixtures.level(this.warehouse, this.frame)).as("remise en stock annulée").isEqualTo(9);
		assertThat(status(sale.id())).isEqualTo("COMPLETED");
	}

	// --- Concurrence ----------------------------------------------------------------------------------

	@Test
	void concurrentSalesNeverOversell() throws Exception {
		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 15; i++) {
			attempts.add(() -> {
				try {
					sell(null, null, item(this.frame, 1));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
					return false;
				}
			});
		}

		assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(10);
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isZero();
		assertThat(saleCount()).isEqualTo(10);
		assertThat(auditCount()).as("une trace par vente réussie, aucune pour les refus").isEqualTo(10);
		assertThat(this.jdbc.queryForObject("select count(distinct number) from sale where warehouse_id = ?",
				Integer.class, this.warehouse)).as("numéros uniques").isEqualTo(10);
	}

	@Test
	void concurrentCancellationsRestockOnce() throws Exception {
		SaleResponse sale = sell(null, null, item(this.frame, 3));

		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			attempts.add(() -> {
				try {
					asSeller(() -> this.sales.cancel(sale.id(), new CancelSaleRequest("Doublon"), this.warehouse));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.SALE_NOT_CANCELLABLE);
					return false;
				}
			});
		}

		assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(10);
	}

	// --- Garde-fous de la base -------------------------------------------------------------------------

	@Test
	void theDatabaseRefusesASaleWithoutLines() {
		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> insertBareSale("VTE-IT-VIDE")))
			.hasStackTraceContaining("vente sans ligne");
		assertThat(saleCount()).isZero();
	}

	@Test
	void theDatabaseRefusesASaleLineWithoutItsStockExit() {
		SaleResponse sale = sell(null, null, item(this.frame, 1));
		// Une sortie de stock qui n'est pas celle de la vente (perte saisie à la main).
		UUID loss = asSeller(() -> this.stock.applyMovement(StockMovementCommand
			.of(this.warehouse, this.lens, StockMovementType.LOSS, -1, StockSourceType.MANUAL, "Casse")
			.withUnitCost(new BigDecimal("4000")))).getId();

		assertThatThrownBy(() -> this.jdbc.update("""
				insert into sale_line (sale_id, line_number, product_id, product_sku, product_name, quantity, unit_price,
				                       line_total, unit_cost, cost_total, stock_movement_id)
				values (?, 2, ?, 'X', 'X', 1, 8000, 8000, 4000, 4000, ?)""", sale.id(), this.lens, loss))
			.hasStackTraceContaining("aucune sortie de stock ne correspond");
	}

	@Test
	void theDatabaseFreezesARecordedSale() {
		SaleResponse sale = sell(null, null, item(this.frame, 1));

		assertThatThrownBy(() -> this.jdbc.update("update sale set total = 1, subtotal = 1, profit = -14999, "
				+ "paid_amount = 1 where id = ?", sale.id()))
			.hasStackTraceContaining("une vente enregistrée ne se modifie pas");
		assertThatThrownBy(() -> this.jdbc.update("delete from sale where id = ?", sale.id()))
			.hasStackTraceContaining("La table sale est en ajout seul");
		// Annuler « à la main » sans remettre le stock : refusé au COMMIT.
		// (Selon l'ordre des contraintes différées : sans remboursement en caisse ou sans remise en stock.)
		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> this.jdbc.update(
				"update sale set status = 'CANCELLED', cancelled_at = now() where id = ?", sale.id())))
			.hasStackTraceContaining("annulée sans");
		assertThat(status(sale.id())).isEqualTo("COMPLETED");
	}

	@Test
	void aCommittedCreditSaleCarriesItsReceivable() {
		SaleResponse sale = sell(this.customer, new BigDecimal("10000"), item(this.frame, 1));

		assertThat(sale.creditAmount()).isEqualByComparingTo("20000");
		assertThat(balance()).isEqualByComparingTo("20000");
		SaleResponse cancelled = asSeller(() -> this.sales.cancel(sale.id(), new CancelSaleRequest("Erreur"), this.warehouse));
		assertThat(cancelled.status()).isEqualTo(SaleStatus.CANCELLED);
		assertThat(balance()).isZero();
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(10);
	}

	// --- Outils ------------------------------------------------------------------------------------------

	private SaleResponse sell(UUID customerId, BigDecimal paidAmount, CreateSaleRequest.Item... items) {
		return asSeller(() -> this.sales.create(new CreateSaleRequest(null, customerId, null, PaymentMethod.CASH,
				paidAmount, null, List.of(items)), this.warehouse));
	}

	private static CreateSaleRequest.Item item(UUID productId, int quantity) {
		return new CreateSaleRequest.Item(productId, quantity, null);
	}

	private void receive(UUID productId, int quantity) {
		asSeller(() -> this.stock.applyMovement(StockMovementCommand.of(this.warehouse, productId,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock initial")));
	}

	/** Vente à crédit sans ligne (rien d'encaissé : seule la contrainte des lignes est en défaut). */
	private void insertBareSale(String number) {
		this.jdbc.update("""
				insert into sale (number, warehouse_id, customer_id, price_type, subtotal, discount_total, total, cost_total,
				                  profit, paid_amount, credit_amount)
				values (?, ?, ?, 'RETAIL', 30000, 0, 30000, 15000, 15000, 0, 30000)""", number, this.warehouse,
				this.customer);
	}

	private int saleCount() {
		return this.jdbc.queryForObject("select count(*) from sale where warehouse_id = ?", Integer.class, this.warehouse);
	}

	private int auditCount() {
		return this.jdbc.queryForObject("select count(*) from audit_log where entity_type = 'SALE' and warehouse_id = ?",
				Integer.class, this.warehouse);
	}

	private String status(UUID saleId) {
		return this.jdbc.queryForObject("select status from sale where id = ?", String.class, saleId);
	}

	private BigDecimal balance() {
		return this.jdbc.queryForObject("""
				select coalesce(sum(case when direction = 'DEBIT' then amount else -amount end), 0)
				from customer_account_entry where customer_id = ?""", BigDecimal.class, this.customer);
	}

	/** Supprime ventes, audit et compte client des tests (journaux en ajout seul : triggers suspendus). */
	private void purgeSales() {
		this.transactions.executeWithoutResult(status -> {
			for (String table : List.of("sale_line", "sale", "audit_log", "customer_account_entry", "cash_entry",
					"cash_session")) {
				this.jdbc.execute("alter table " + table + " disable trigger user");
			}
			this.jdbc.update("delete from cash_entry where session_id in (select id from cash_session where warehouse_id = ?)",
					this.warehouse);
			this.jdbc.update("delete from cash_session where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from sale_line where sale_id in (select id from sale where warehouse_id = ?)",
					this.warehouse);
			this.jdbc.update("delete from sale where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from audit_log where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from customer_account_entry where customer_id = ?", this.customer);
			for (String table : List.of("sale_line", "sale", "audit_log", "customer_account_entry", "cash_entry",
					"cash_session")) {
				this.jdbc.execute("alter table " + table + " enable trigger user");
			}
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
