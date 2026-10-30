package com.geststock.stock;

import static com.geststock.support.BusinessScenario.as;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.integration.ReservedQuantities;
import com.geststock.integration.LinkRef;
import com.geststock.integration.WarehouseScope;
import com.geststock.stock.StockAvailabilityProvider.Availability;
import com.geststock.stock.StockAvailabilityProvider.WarehouseAvailability;
import com.geststock.support.PostgresTestConfiguration;

/**
 * Fournisseur de disponibilité (lot 1, étape 4c) sur un vrai PostgreSQL : stock des dépôts du périmètre moins
 * les réservations, jamais négatif, détail par dépôt cohérent avec le total, une seule requête SQL pour tous les
 * produits d'une page.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class StockAvailabilityProviderIT {

	private static final LinkRef LINK = new LinkRef(UUID.randomUUID());

	private static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

	@Autowired
	private StockService stock;

	@Autowired
	private WarehouseScope linkWarehouses;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private UUID actor;

	private UUID store;

	private UUID depot;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("dispo");
		this.store = this.fixtures.warehouse("magasin");
		this.depot = this.fixtures.warehouse("depot");
	}

	@AfterEach
	void tearDown() {
		this.fixtures.purge();
		this.jdbc.update("delete from integration_outbox");
	}

	@Test
	void availabilityIsTheStockOfTheScopeWithADetailPerWarehouse() {
		UUID product = this.fixtures.product("monture", 1000);
		receive(this.store, product, 7);
		receive(this.depot, product, 5);

		Availability value = provider(Set.of(this.store, this.depot), noReservation()).availability(LINK, Set.of(product))
			.get(product);

		assertThat(value.productId()).isEqualTo(product);
		assertThat(value.available()).isEqualTo(12);
		assertThat(value.perWarehouse()).containsExactlyInAnyOrder(new WarehouseAvailability(this.store, 7),
				new WarehouseAvailability(this.depot, 5));
		assertThat(value.computedAt()).isEqualTo(NOW);
	}

	@Test
	void reservationsAreSubtractedAndTheValueIsNeverNegative() {
		UUID product = this.fixtures.product("monture", 1000);
		receive(this.store, product, 3);
		receive(this.depot, product, 10);
		ReservedQuantities reserved = (products, warehouses) -> Map.of(product,
				Map.of(this.store, 5, this.depot, 4));

		Availability value = provider(Set.of(this.store, this.depot), reserved).availability(LINK, Set.of(product)).get(product);

		assertThat(value.perWarehouse()).containsExactlyInAnyOrder(new WarehouseAvailability(this.store, 0),
				new WarehouseAvailability(this.depot, 6));
		assertThat(value.available()).as("total = somme du détail, jamais négatif").isEqualTo(6)
			.isEqualTo(value.perWarehouse().stream().mapToInt(WarehouseAvailability::available).sum());
	}

	@Test
	void onlyTheWarehousesOfTheScopeCountAndAProductWithoutStockIsZero() {
		UUID product = this.fixtures.product("monture", 1000);
		UUID unknown = UUID.randomUUID();
		receive(this.store, product, 4);
		receive(this.depot, product, 6);

		Map<UUID, Availability> values = provider(Set.of(this.store), noReservation()).availability(LINK, Set.of(product, unknown));

		assertThat(values.get(product).available()).isEqualTo(4);
		assertThat(values.get(product).perWarehouse()).containsExactly(new WarehouseAvailability(this.store, 4));
		assertThat(values.get(unknown).available()).isZero();
		assertThat(values.get(unknown).perWarehouse()).isEmpty();
		assertThat(provider(Set.of(), noReservation()).availability(LINK, Set.of(product)).get(product).available())
			.as("périmètre vide").isZero();
	}

	/** G4a : le périmètre est celui de la liaison (ses dépôts encore actifs), jamais « tous les dépôts ». */
	@Test
	void theScopeOfALinkIsItsWarehousesThatAreStillActive() {
		assertThat(this.linkWarehouses.warehouseIds(LINK)).as("liaison sans dépôt").isEmpty();
		try {
			for (UUID warehouse : List.of(this.store, this.depot)) {
				this.jdbc.update("insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)",
						LINK.id(), warehouse, this.actor);
			}
			assertThat(this.linkWarehouses.warehouseIds(LINK)).containsExactlyInAnyOrder(this.store, this.depot);
			this.jdbc.update("update warehouse set active = false where id = ?", this.depot);
			assertThat(this.linkWarehouses.warehouseIds(LINK)).containsExactly(this.store);
		}
		finally {
			this.jdbc.update("delete from integration_link_warehouse where link_id = ?", LINK.id());
		}
	}

	@Test
	void fiveHundredProductsAreReadWithASingleQuery() {
		Set<UUID> products = new HashSet<>();
		for (int i = 0; i < 500; i++) {
			products.add(UUID.randomUUID());
		}
		UUID stocked = this.fixtures.product("monture", 1000);
		receive(this.store, stocked, 2);
		products.add(stocked);

		CountingDataSource counting = new CountingDataSource(this.dataSource);
		Map<UUID, Availability> values = new StockAvailabilityProvider(counting, link -> Set.of(this.store, this.depot),
				noReservation(), Clock.fixed(NOW, ZoneOffset.UTC)).availability(LINK, products);

		assertThat(values).hasSize(501);
		assertThat(values.get(stocked).available()).isEqualTo(2);
		assertThat(counting.statements.get()).as("requêtes SQL du fournisseur").isEqualTo(1);
	}

	@Test
	void theQueryCanUseTheStockLevelIndexes() {
		List<String> plan = this.transactions.execute(status -> {
			this.jdbc.execute("set local enable_seqscan = off");
			return this.jdbc.queryForList("explain " + StockAvailabilityProvider.LEVELS
				.replace(":productIds", "'" + UUID.randomUUID() + "'::uuid, '" + UUID.randomUUID() + "'::uuid")
				.replace(":warehouseIds", "'" + this.store + "'::uuid"), String.class);
		});
		assertThat(String.join("\n", plan)).containsAnyOf("pk_stock_level", "ix_stock_level_product_id");
	}

	// --- Outils ------------------------------------------------------------------------------------------

	private StockAvailabilityProvider provider(Set<UUID> scope, ReservedQuantities reserved) {
		return new StockAvailabilityProvider(this.dataSource, link -> scope, reserved, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ReservedQuantities noReservation() {
		return (products, warehouses) -> Map.of();
	}

	private void receive(UUID warehouse, UUID product, int quantity) {
		as(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(warehouse, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock initial")));
	}

	/** Compte les requêtes préparées sur les connexions qu'il fournit. */
	static final class CountingDataSource extends DelegatingDataSource {

		final AtomicInteger statements = new AtomicInteger();

		CountingDataSource(DataSource target) {
			super(target);
		}

		@Override
		public Connection getConnection() throws SQLException {
			Connection connection = super.getConnection();
			return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						String name = method.getName();
						if (name.startsWith("prepareStatement") || name.equals("createStatement")
								|| name.startsWith("prepareCall")) {
							this.statements.incrementAndGet();
						}
						try {
							return method.invoke(connection, args);
						}
						catch (InvocationTargetException ex) {
							throw ex.getCause();
						}
					});
		}

	}

}
