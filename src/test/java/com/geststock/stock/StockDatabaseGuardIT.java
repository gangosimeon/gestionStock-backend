package com.geststock.stock;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.support.PostgresTestConfiguration;

import static com.geststock.stock.StockFixtures.runAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garde-fous de PostgreSQL : même un code qui contournerait {@link StockService} (requête
 * SQL directe, autre application) ne peut ni écrire un niveau sans mouvement, ni modifier
 * ou effacer l'historique, ni rendre le stock négatif.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class StockDatabaseGuardIT {

	@Autowired
	private StockService stock;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private UUID warehouse;

	private UUID product;

	private StockMovement entry;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		UUID actor = this.fixtures.user("garde");
		this.warehouse = this.fixtures.warehouse("garde");
		this.product = this.fixtures.product("garde", 1000);
		this.entry = runAs(actor, () -> this.stock.applyMovement(StockMovementCommand.of(this.warehouse, this.product,
				StockMovementType.RECEIPT, 5, StockSourceType.MANUAL, null)));
	}

	@AfterEach
	void tearDown() {
		this.fixtures.purge();
	}

	@Test
	void levelCannotBeChangedWithoutAMovement() {
		assertThatThrownBy(() -> this.jdbc.update(
				"update stock_level set quantity = 500 where warehouse_id = ? and product_id = ?", this.warehouse,
				this.product))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("modification directe interdite");
		assertThat(this.fixtures.level(this.warehouse, this.product)).isEqualTo(5);
	}

	@Test
	void levelCannotReuseOrInventAMovement() {
		// Réutiliser un mouvement existant d'un autre couple, ou un identifiant inventé.
		assertThatThrownBy(() -> this.jdbc.update("""
				update stock_level set quantity = 10, last_movement_id = ?
				where warehouse_id = ? and product_id = ?""", UUID.randomUUID(), this.warehouse, this.product))
			.isInstanceOf(DataAccessException.class);
		assertThat(this.fixtures.level(this.warehouse, this.product)).isEqualTo(5);
	}

	@Test
	void levelIsCreatedAtZeroAndNeverDeleted() {
		UUID other = this.fixtures.product("autre", 1000);
		assertThatThrownBy(() -> this.jdbc.update(
				"insert into stock_level (warehouse_id, product_id, quantity) values (?, ?, 50)", this.warehouse, other))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("créée à zéro");
		assertThatThrownBy(() -> this.jdbc.update("delete from stock_level where warehouse_id = ?", this.warehouse))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ajout seul");
	}

	@Test
	void historyIsAppendOnly() {
		assertThatThrownBy(() -> this.jdbc.update("update stock_movement set quantity = 50 where id = ?",
				this.entry.getId()))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ajout seul");
		assertThatThrownBy(() -> this.jdbc.update("delete from stock_movement where id = ?", this.entry.getId()))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ajout seul");
	}

	@Test
	void movementDirectionAndNonNegativeBalanceAreEnforced() {
		UUID user = this.jdbc.queryForObject("select created_by from stock_movement where id = ?", UUID.class,
				this.entry.getId());
		assertThatThrownBy(() -> this.jdbc.update("""
				insert into stock_movement (id, warehouse_id, product_id, type, quantity, balance_after, source_type, created_by)
				values (?, ?, ?, 'SALE', 3, 8, 'MANUAL', ?)""", UUID.randomUUID(), this.warehouse, this.product, user))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_stock_movement_direction");
		assertThatThrownBy(() -> this.jdbc.update("""
				insert into stock_movement (id, warehouse_id, product_id, type, quantity, balance_after, source_type, created_by)
				values (?, ?, ?, 'SALE', -9, -4, 'MANUAL', ?)""", UUID.randomUUID(), this.warehouse, this.product, user))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_stock_movement_balance_after");
	}

}
