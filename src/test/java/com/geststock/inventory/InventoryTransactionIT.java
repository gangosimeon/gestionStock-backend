package com.geststock.inventory;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.common.security.Permissions;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.inventory.dto.CreateInventoryRequest;
import com.geststock.inventory.dto.InventoryCountsRequest;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Inventaires en transactions réelles (COMMIT / ROLLBACK) : atomicité de la validation,
 * concurrence, et garde-fous de la base (inventaire terminé figé, lignes hors étape,
 * clôture sans application des écarts).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class InventoryTransactionIT {

	@Autowired
	private InventoryService inventories;

	@Autowired
	private StockService stock;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private UUID actor;

	private UUID warehouse;

	private UUID frame;

	private UUID lens;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("inventoriste");
		this.warehouse = this.fixtures.warehouse("inventaire");
		this.frame = this.fixtures.product("monture", 15000);
		this.lens = this.fixtures.product("verre", 4000);
		move(this.frame, StockMovementType.RECEIPT, 10);
		move(this.lens, StockMovementType.RECEIPT, 2);
	}

	@AfterEach
	void tearDown() {
		assertThat(this.fixtures.invariantViolations()).as("niveau = somme des mouvements").isEmpty();
		this.transactions.executeWithoutResult(status -> {
			for (String table : List.of("inventory_line", "inventory_session", "audit_log")) {
				this.jdbc.execute("alter table " + table + " disable trigger user");
			}
			this.jdbc.update("""
					delete from inventory_line
					where inventory_id in (select id from inventory_session where warehouse_id = ?)""", this.warehouse);
			this.jdbc.update("delete from inventory_session where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from audit_log where warehouse_id = ?", this.warehouse);
			for (String table : List.of("inventory_line", "inventory_session", "audit_log")) {
				this.jdbc.execute("alter table " + table + " enable trigger user");
			}
		});
		this.fixtures.purge();
	}

	@Test
	void aValidationThatCannotApplyEveryGapAppliesNone() {
		UUID id = submitted(12, 0); // monture +2, verre −2
		move(this.lens, StockMovementType.SALE, -2); // verres vendus après le comptage : 0 en stock

		assertThatThrownBy(() -> as(() -> this.inventories.validate(id, null, this.warehouse)))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.STOCK_INSUFFICIENT);
		assertThat(this.fixtures.level(this.warehouse, this.frame)).as("écart de la monture non appliqué").isEqualTo(10);
		assertThat(status(id)).isEqualTo("VALIDATION");
		assertThat(this.jdbc.queryForObject("select count(*) from stock_movement where source_id = ?", Integer.class, id))
			.isZero();

		// Reprise : recomptage du verre (théorique 0, compté 0), puis validation.
		as(() -> this.inventories.reopen(id, "Recompter les verres", this.warehouse));
		as(() -> this.inventories.recordCounts(id, List.of(new InventoryCountsRequest.Count(this.lens, 0, null)), this.warehouse));
		as(() -> this.inventories.submit(id, this.warehouse));
		as(() -> this.inventories.validate(id, null, this.warehouse));
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(12);
		assertThat(status(id)).isEqualTo("COMPLETED");
	}

	@Test
	void concurrentValidationsApplyTheGapsOnce() throws Exception {
		UUID id = submitted(13, 2);

		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			attempts.add(() -> {
				try {
					as(() -> this.inventories.validate(id, null, this.warehouse));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.INVENTORY_INVALID_TRANSITION);
					return false;
				}
			});
		}

		assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(13);
	}

	@Test
	void onlyOneInventoryCanBeOpenedPerWarehouseEvenConcurrently() throws Exception {
		List<Callable<Boolean>> attempts = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			attempts.add(() -> {
				try {
					create();
					return true;
				}
				catch (ApiException | DataIntegrityViolationException ex) {
					return false;
				}
			});
		}

		assertThat(runConcurrently(attempts)).filteredOn(Boolean::booleanValue).hasSize(1);
	}

	@Test
	void theDatabaseFreezesACompletedInventory() {
		UUID id = submitted(9, 2);
		as(() -> this.inventories.validate(id, null, this.warehouse));

		assertThatThrownBy(() -> this.jdbc.update("update inventory_session set note = 'retouche' where id = ?", id))
			.hasStackTraceContaining("un inventaire terminé ou annulé ne se modifie plus");
		assertThatThrownBy(() -> this.jdbc.update(
				"update inventory_line set counted_quantity = 100, difference = 90 where inventory_id = ? and product_id = ?",
				id, this.frame))
			.hasStackTraceContaining("comptage modifié hors comptage");
		assertThatThrownBy(() -> this.jdbc.update("delete from inventory_session where id = ?", id))
			.hasStackTraceContaining("La table inventory_session est en ajout seul");
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(9);
	}

	@Test
	void theDatabaseRefusesLinesOutsideTheDraftAndACompletionWithoutStockMovements() {
		UUID id = submitted(7, 2);

		assertThatThrownBy(() -> this.jdbc.update("""
				insert into inventory_line (inventory_id, line_number, product_id, product_sku, product_name)
				values (?, 99, ?, 'X', 'X')""", id, this.fixtures.product("ajout", 100)))
			.hasStackTraceContaining("produit ajouté hors brouillon");

		// Clôturer « à la main » sans appliquer l'écart de la monture : refusé au COMMIT.
		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> this.jdbc.update("""
				update inventory_session set status = 'COMPLETED', validated_at = now(), validated_by = ? where id = ?""",
				this.actor, id)))
			.hasStackTraceContaining("écart non appliqué exactement au stock");
		assertThat(status(id)).isEqualTo("VALIDATION");
		assertThat(this.fixtures.level(this.warehouse, this.frame)).isEqualTo(10);
	}

	// --- Outils ------------------------------------------------------------------------------------

	/** Inventaire des deux produits, compté (monture, verre) et soumis à validation. */
	private UUID submitted(int frameCount, int lensCount) {
		UUID id = create();
		as(() -> this.inventories.start(id, this.warehouse));
		as(() -> this.inventories.recordCounts(id, List.of(new InventoryCountsRequest.Count(this.frame, frameCount, null),
				new InventoryCountsRequest.Count(this.lens, lensCount, null)), this.warehouse));
		as(() -> this.inventories.submit(id, this.warehouse));
		return id;
	}

	private UUID create() {
		return as(() -> this.inventories.create(new CreateInventoryRequest(null, InventoryScope.SELECTION, null,
				List.of(this.frame, this.lens), null), this.warehouse)).id();
	}

	private String status(UUID id) {
		return this.jdbc.queryForObject("select status from inventory_session where id = ?", String.class, id);
	}

	private void move(UUID productId, StockMovementType type, int quantity) {
		as(() -> this.stock.applyMovement(
				StockMovementCommand.of(this.warehouse, productId, type, quantity, StockSourceType.MANUAL, "Test")));
	}

	private <T> T as(Supplier<T> action) {
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
