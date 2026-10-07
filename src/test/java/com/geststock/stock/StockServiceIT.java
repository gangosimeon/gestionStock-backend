package com.geststock.stock;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.UnauthorizedException;
import com.geststock.support.PostgresTestConfiguration;

import static com.geststock.stock.StockFixtures.runAs;
import static com.geststock.stock.StockMovementType.ADJUSTMENT;
import static com.geststock.stock.StockMovementType.CUSTOMER_RETURN;
import static com.geststock.stock.StockMovementType.LOSS;
import static com.geststock.stock.StockMovementType.RECEIPT;
import static com.geststock.stock.StockMovementType.SALE;
import static com.geststock.stock.StockMovementType.SALE_CANCELLATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StockService} sur PostgreSQL, transactions réelles (pas de rollback automatique du
 * test) : entrées, sorties, refus, annulations, atomicité, multi-dépôts, concurrence et
 * cohérence de l'historique.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class StockServiceIT {

	@Autowired
	private StockService stock;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private UUID actor;

	private UUID store;

	private UUID depot;

	private UUID frame;

	private UUID lens;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("service");
		this.store = this.fixtures.warehouse("magasin");
		this.depot = this.fixtures.warehouse("dépôt");
		this.frame = this.fixtures.product("monture", 15000);
		this.lens = this.fixtures.product("verre", 4000);
	}

	@AfterEach
	void tearDown() {
		assertThat(this.fixtures.invariantViolations()).as("niveau = somme des mouvements = dernier solde").isEmpty();
		this.fixtures.purge();
	}

	// --- Entrées et sorties -------------------------------------------------------------

	@Test
	void entryCreatesTheLevelAndRecordsAFullMovement() {
		StockMovement movement = apply(RECEIPT, this.store, this.frame, 10);

		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(10);
		assertThat(movement.getId().version()).as("UUID v7").isEqualTo(7);
		assertThat(movement.getQuantity()).isEqualTo(10);
		assertThat(movement.getBalanceAfter()).isEqualTo(10);
		assertThat(movement.getCreatedBy()).as("utilisateur courant").isEqualTo(this.actor);
		assertThat(movement.getUnitCost()).as("prix d'achat par défaut").isEqualByComparingTo("15000");
		assertThat(movement.getCreatedAt()).isNotNull();
		assertThat(this.jdbc.queryForObject("select last_movement_id from stock_level where warehouse_id = ? "
				+ "and product_id = ?", UUID.class, this.store, this.frame)).isEqualTo(movement.getId());
	}

	@Test
	void exitDecreasesTheLevel() {
		apply(RECEIPT, this.store, this.frame, 10);

		StockMovement sale = apply(SALE, this.store, this.frame, -4);
		StockMovement loss = apply(LOSS, this.store, this.frame, -1);

		assertThat(sale.getBalanceAfter()).isEqualTo(6);
		assertThat(loss.getBalanceAfter()).isEqualTo(5);
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(5);
	}

	@Test
	void positiveAndNegativeAdjustments() {
		apply(ADJUSTMENT, this.store, this.frame, 7);
		apply(ADJUSTMENT, this.store, this.frame, -2);

		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(5);
	}

	@Test
	void unitCostCanBeGivenExplicitly() {
		StockMovement movement = runAs(this.actor, () -> this.stock.applyMovement(
				StockMovementCommand.of(this.store, this.frame, RECEIPT, 3, StockSourceType.PURCHASE_ORDER, null)
					.withUnitCost(new BigDecimal("14250.50"))
					.withSource(UUID.randomUUID(), "PO-2026-0001")));

		assertThat(movement.getUnitCost()).isEqualByComparingTo("14250.50");
		assertThat(movement.getSourceReference()).isEqualTo("PO-2026-0001");
	}

	// --- Refus ----------------------------------------------------------------------------

	@Test
	void cannotSellMoreThanAvailableAndNothingIsWritten() {
		apply(RECEIPT, this.store, this.frame, 6);

		assertThatThrownBy(() -> apply(SALE, this.store, this.frame, -7))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
				assertThat(ex.getMessage()).isEqualTo("Stock insuffisant.");
				assertThat(ex.getDetails()).containsEntry("available", 6).containsEntry("requested", 7);
			});
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(6);
		assertThat(this.fixtures.movementCount(this.store, this.frame)).isEqualTo(1);
	}

	@Test
	void saleOnAnEmptyProductIsRefused() {
		assertThatThrownBy(() -> apply(SALE, this.store, this.frame, -1))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT));
		assertThat(this.fixtures.movementCount(this.store, this.frame)).isZero();
	}

	@Test
	void quantitySignMustMatchTheMovementType() {
		assertThatThrownBy(() -> apply(SALE, this.store, this.frame, 3))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_STOCK_QUANTITY));
		assertThatThrownBy(() -> apply(RECEIPT, this.store, this.frame, -3))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_STOCK_QUANTITY));
		assertThatThrownBy(() -> apply(ADJUSTMENT, this.store, this.frame, 0))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_STOCK_QUANTITY));
	}

	@Test
	void warehouseAndProductMustExistAndWarehouseBeActive() {
		this.jdbc.update("update warehouse set active = false where id = ?", this.depot);

		assertThatThrownBy(() -> apply(RECEIPT, this.depot, this.frame, 1))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.UNKNOWN_WAREHOUSE));
		assertThatThrownBy(() -> apply(RECEIPT, this.store, UUID.randomUUID(), 1))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
	}

	@Test
	void anAuthenticatedUserIsMandatory() {
		assertThatThrownBy(() -> this.stock.applyMovement(
				StockMovementCommand.of(this.store, this.frame, RECEIPT, 1, StockSourceType.MANUAL, null)))
			.isInstanceOf(UnauthorizedException.class);
		assertThat(this.fixtures.movementCount(this.store, this.frame)).isZero();
	}

	// --- Atomicité --------------------------------------------------------------------------

	@Test
	void aBatchIsAllOrNothing() {
		apply(RECEIPT, this.store, this.lens, 2);

		assertThatThrownBy(() -> runAs(this.actor, () -> this.stock.applyMovements(List.of(
				command(RECEIPT, this.store, this.frame, 5),
				command(SALE, this.store, this.lens, -3)))))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT));

		assertThat(this.fixtures.level(this.store, this.frame)).as("entrée annulée avec le lot").isZero();
		assertThat(this.fixtures.movementCount(this.store, this.frame)).isZero();
		assertThat(this.fixtures.level(this.store, this.lens)).isEqualTo(2);
	}

	@Test
	void aFailureOfTheCallerRollsBackTheStock() {
		// Comme une vente qui échouerait après avoir décrémenté le stock (paiement refusé…).
		assertThatThrownBy(() -> runAs(this.actor, () -> this.transactions.execute(status -> {
			this.stock.applyMovement(command(RECEIPT, this.store, this.frame, 5));
			throw new IllegalStateException("échec de l'appelant");
		}))).hasMessage("échec de l'appelant");

		assertThat(this.fixtures.level(this.store, this.frame)).isZero();
		assertThat(this.fixtures.movementCount(this.store, this.frame)).isZero();
	}

	@Test
	void severalMovementsOfTheSameProductInOneBatchChainTheirBalances() {
		List<StockMovement> written = runAs(this.actor, () -> this.stock.applyMovements(List.of(
				command(RECEIPT, this.store, this.frame, 5),
				command(SALE, this.store, this.frame, -2),
				command(RECEIPT, this.store, this.frame, 1))));

		assertThat(written).extracting(StockMovement::getBalanceAfter).containsExactly(5, 3, 4);
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(4);
	}

	// --- Plusieurs dépôts --------------------------------------------------------------------

	@Test
	void eachWarehouseHasItsOwnStock() {
		apply(RECEIPT, this.store, this.frame, 8);
		apply(RECEIPT, this.depot, this.frame, 3);

		assertThatThrownBy(() -> apply(SALE, this.depot, this.frame, -4))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT));
		apply(SALE, this.store, this.frame, -4);

		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(4);
		assertThat(this.fixtures.level(this.depot, this.frame)).isEqualTo(3);
	}

	@Test
	void transferMovesStockAtomicallyBetweenWarehouses() {
		apply(RECEIPT, this.depot, this.frame, 10);

		List<StockMovement> legs = runAs(this.actor,
				() -> this.stock.transfer(this.depot, this.store, this.frame, 4, null));

		assertThat(legs).extracting(StockMovement::getType)
			.containsExactly(StockMovementType.TRANSFER_OUT, StockMovementType.TRANSFER_IN);
		assertThat(legs).extracting(StockMovement::getQuantity).containsExactly(-4, 4);
		assertThat(legs.get(0).getCorrelationId()).isNotNull().isEqualTo(legs.get(1).getCorrelationId());
		assertThat(this.fixtures.level(this.depot, this.frame)).isEqualTo(6);
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(4);

		assertThatThrownBy(() -> runAs(this.actor, () -> this.stock.transfer(this.depot, this.store, this.frame, 7, null)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT));
		assertThat(this.fixtures.level(this.store, this.frame)).as("aucune entrée sans sortie").isEqualTo(4);
		assertThatThrownBy(() -> runAs(this.actor, () -> this.stock.transfer(this.depot, this.depot, this.frame, 1, null)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.SAME_WAREHOUSE_TRANSFER));
	}

	// --- Annulations --------------------------------------------------------------------------

	@Test
	void saleCancellationAndCustomerReturnPutStockBack() {
		apply(RECEIPT, this.store, this.frame, 5);
		UUID saleId = UUID.randomUUID();
		runAs(this.actor, () -> this.stock.applyMovement(
				command(SALE, this.store, this.frame, -3).withSource(saleId, "V-0001")));

		runAs(this.actor, () -> this.stock.applyMovement(
				StockMovementCommand.of(this.store, this.frame, SALE_CANCELLATION, 3, StockSourceType.SALE, "Annulation")
					.withSource(saleId, "V-0001")));
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(5);

		runAs(this.actor, () -> this.stock.applyMovement(
				StockMovementCommand.of(this.store, this.frame, CUSTOMER_RETURN, 1, StockSourceType.CUSTOMER_RETURN, null)));
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(6);
	}

	@Test
	void manualMovementIsReversedOnceByAnOppositeMovement() {
		StockMovement entry = apply(RECEIPT, this.store, this.frame, 5);

		StockMovement reversal = runAs(this.actor, () -> this.stock.reverse(entry.getId(), "Erreur de saisie"));

		assertThat(reversal.getType()).isEqualTo(StockMovementType.REVERSAL);
		assertThat(reversal.getQuantity()).isEqualTo(-5);
		assertThat(reversal.getReversalOfId()).isEqualTo(entry.getId());
		assertThat(this.fixtures.level(this.store, this.frame)).isZero();
		assertThat(this.fixtures.movementCount(this.store, this.frame)).as("l'original reste dans l'historique")
			.isEqualTo(2);

		assertThatThrownBy(() -> runAs(this.actor, () -> this.stock.reverse(entry.getId(), null)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.MOVEMENT_ALREADY_REVERSED));
		assertThatThrownBy(() -> runAs(this.actor, () -> this.stock.reverse(reversal.getId(), null)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.MOVEMENT_NOT_REVERSIBLE));
	}

	@Test
	void consumedEntryCannotBeReversedAndDocumentMovementsAreNotReversibleHere() {
		StockMovement entry = apply(RECEIPT, this.store, this.frame, 5);
		apply(SALE, this.store, this.frame, -4);

		assertThatThrownBy(() -> runAs(this.actor, () -> this.stock.reverse(entry.getId(), null)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT));

		StockMovement transferOut = runAs(this.actor,
				() -> this.stock.transfer(this.store, this.depot, this.frame, 1, null)).get(0);
		assertThatThrownBy(() -> runAs(this.actor, () -> this.stock.reverse(transferOut.getId(), null)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.MOVEMENT_NOT_REVERSIBLE));
	}

	// --- Inventaire ----------------------------------------------------------------------------

	@Test
	void inventoryAdjustsByTheValidatedDifference() {
		apply(RECEIPT, this.store, this.frame, 10);
		apply(RECEIPT, this.store, this.lens, 4);
		UUID session = UUID.randomUUID();

		List<StockMovement> gaps = runAs(this.actor, () -> this.stock.applyInventoryCounts(this.store,
				Map.of(this.frame, 8, this.lens, 4), session, "INV-0001"));

		assertThat(gaps).singleElement().satisfies(gap -> {
			assertThat(gap.getType()).isEqualTo(StockMovementType.INVENTORY);
			assertThat(gap.getQuantity()).isEqualTo(-2);
			assertThat(gap.getSourceId()).isEqualTo(session);
		});
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(8);
		assertThat(this.fixtures.level(this.store, this.lens)).isEqualTo(4);

		runAs(this.actor, () -> this.stock.applyInventoryCounts(this.store, Map.of(this.frame, 11), session, null));
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(11);
	}

	// --- Concurrence ---------------------------------------------------------------------------

	@Test
	void concurrentSalesNeverOversell() throws Exception {
		apply(RECEIPT, this.store, this.frame, 10);

		List<Callable<Boolean>> sales = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			sales.add(() -> {
				try {
					apply(SALE, this.store, this.frame, -1);
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
					return false;
				}
			});
		}
		List<Boolean> outcomes = runConcurrently(sales);

		assertThat(outcomes).filteredOn(Boolean::booleanValue).hasSize(10);
		assertThat(this.fixtures.level(this.store, this.frame)).isZero();
		assertThat(this.fixtures.movementCount(this.store, this.frame)).isEqualTo(11);
		assertThat(this.jdbc.queryForList("""
				select balance_after from stock_movement where warehouse_id = ? and product_id = ? and type = 'SALE'
				order by balance_after desc""", Integer.class, this.store, this.frame))
			.as("chaque vente voit le solde laissé par la précédente").containsExactly(9, 8, 7, 6, 5, 4, 3, 2, 1, 0);
	}

	@Test
	void concurrentFirstEntriesOnANewLevelAreAllCounted() throws Exception {
		List<Callable<Boolean>> entries = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			entries.add(() -> {
				apply(RECEIPT, this.depot, this.lens, 1);
				return true;
			});
		}
		runConcurrently(entries);

		assertThat(this.fixtures.level(this.depot, this.lens)).isEqualTo(20);
	}

	@Test
	void crossedBatchesDoNotDeadlock() throws Exception {
		apply(RECEIPT, this.store, this.frame, 100);
		apply(RECEIPT, this.store, this.lens, 100);

		List<Callable<Boolean>> batches = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			boolean frameFirst = i % 2 == 0;
			batches.add(() -> {
				runAs(this.actor, () -> this.stock.applyMovements(frameFirst
						? List.of(command(SALE, this.store, this.frame, -1), command(SALE, this.store, this.lens, -1))
						: List.of(command(SALE, this.store, this.lens, -1), command(SALE, this.store, this.frame, -1))));
				return true;
			});
		}
		assertThat(runConcurrently(batches)).hasSize(20).allMatch(Boolean::booleanValue);

		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(80);
		assertThat(this.fixtures.level(this.store, this.lens)).isEqualTo(80);
	}

	@Test
	void concurrentReversalsOfTheSameMovementSucceedOnlyOnce() throws Exception {
		StockMovement entry = apply(RECEIPT, this.store, this.frame, 5);

		List<Callable<Boolean>> reversals = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			reversals.add(() -> {
				try {
					runAs(this.actor, () -> this.stock.reverse(entry.getId(), null));
					return true;
				}
				catch (ApiException | org.springframework.dao.DataIntegrityViolationException ex) {
					return false;
				}
			});
		}

		assertThat(runConcurrently(reversals)).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(this.fixtures.level(this.store, this.frame)).isZero();
	}

	// --- Outils ----------------------------------------------------------------------------------

	private StockMovement apply(StockMovementType type, UUID warehouse, UUID product, int quantity) {
		return runAs(this.actor, () -> this.stock.applyMovement(command(type, warehouse, product, quantity)));
	}

	private static StockMovementCommand command(StockMovementType type, UUID warehouse, UUID product, int quantity) {
		return StockMovementCommand.of(warehouse, product, type, quantity, StockSourceType.MANUAL, null);
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
