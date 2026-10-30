package com.geststock.integration;

import static com.geststock.support.BusinessScenario.as;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.BusinessScenario;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.warehouse.WarehouseService;

import tools.jackson.databind.JsonNode;

/**
 * Disponibilité republiée quand un dépôt change d'état (lot 1) : la désactivation ou la réactivation réelle d'un
 * dépôt publie, dans la même transaction, un événement {@code AVAILABILITY} pour chaque produit qui a un niveau de
 * stock dans ce dépôt ; la valeur servie suit le nouveau périmètre (dépôts actifs).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class WarehouseAvailabilityIT {

	/** Liaison qui lit le flux (G4a : la disponibilité servie est celle de ses dépôts). */
	private static final LinkRef LINK = new LinkRef(UUID.randomUUID());

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private WarehouseService warehouses;

	@Autowired
	private StockService stock;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private BusinessScenario scenario;

	private UUID actor;

	private UUID store;

	private UUID depot;

	/** En stock au magasin (5) et au dépôt (3). */
	private UUID both;

	/** En stock au dépôt seulement (4). */
	private UUID depotOnly;

	/** En stock au magasin seulement (6) : jamais concerné par le dépôt. */
	private UUID storeOnly;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.scenario = new BusinessScenario(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("depot-etat");
		this.store = this.fixtures.warehouse("magasin");
		this.depot = this.fixtures.warehouse("depot");
		this.both = this.fixtures.product("deux-depots", 1000);
		this.depotOnly = this.fixtures.product("depot-seul", 1000);
		this.storeOnly = this.fixtures.product("magasin-seul", 1000);
		receive(this.store, this.both, 5);
		receive(this.depot, this.both, 3);
		receive(this.depot, this.depotOnly, 4);
		receive(this.store, this.storeOnly, 6);
		resetFeed();
		// G4a : la disponibilité servie est celle des dépôts de la liaison ; ce test compare au stock de tous les
		// dépôts actifs (périmètre du lot 1) : la liaison qui lit reçoit donc tous les dépôts actifs.
		this.jdbc.update("insert into integration_link_warehouse (link_id, warehouse_id, added_by) "
				+ "select ?, id, ? from warehouse where active", LINK.id(), LINK.id());
	}

	@AfterEach
	void tearDown() {
		this.jdbc.update("delete from integration_link_warehouse where link_id = ?", LINK.id());
		this.jdbc.update("update warehouse set active = true where id = any(?)", (Object) new UUID[] { this.store, this.depot });
		this.scenario.purge(List.of(this.store, this.depot), List.of(), List.of());
		this.fixtures.purge();
		resetFeed();
	}

	@Test
	void deactivatingAWarehousePublishesItsProductsAndTheServedValueNoLongerCountsIt() {
		as(this.actor, () -> this.warehouses.setActive(this.depot, false));

		assertThat(availabilityEvents()).containsExactlyInAnyOrder(this.both, this.depotOnly);
		JsonNode both = served(this.both);
		assertThat(both.get("available").asInt()).isEqualTo(5);
		assertThat(warehousesOf(both)).containsExactly(this.store.toString());
		JsonNode depotOnly = served(this.depotOnly);
		assertThat(depotOnly.get("available").asInt()).isZero();
		assertThat(depotOnly.get("perWarehouse")).isEmpty();
	}

	@Test
	void reactivatingAWarehousePublishesItsProductsAndTheServedValueCountsItAgain() {
		as(this.actor, () -> this.warehouses.setActive(this.depot, false));
		resetFeed();

		as(this.actor, () -> this.warehouses.setActive(this.depot, true));

		assertThat(availabilityEvents()).containsExactlyInAnyOrder(this.both, this.depotOnly);
		JsonNode both = served(this.both);
		assertThat(both.get("available").asInt()).isEqualTo(8);
		assertThat(warehousesOf(both)).containsExactlyInAnyOrder(this.store.toString(), this.depot.toString());
		assertThat(served(this.depotOnly).get("available").asInt()).isEqualTo(4);
	}

	@Test
	void noRealChangeOfStatePublishesNothing() {
		as(this.actor, () -> this.warehouses.setActive(this.depot, false));
		resetFeed();

		as(this.actor, () -> this.warehouses.setActive(this.depot, false));
		as(this.actor, () -> this.warehouses.setActive(this.store, true));

		assertThat(availabilityEvents()).isEmpty();
	}

	@Test
	void aRolledBackChangeOfStatePublishesNothing() {
		this.transactions.executeWithoutResult(status -> {
			as(this.actor, () -> this.warehouses.setActive(this.depot, false));
			status.setRollbackOnly();
		});

		assertThat(availabilityEvents()).isEmpty();
		assertThat(this.jdbc.queryForObject("select active from warehouse where id = ?", Boolean.class, this.depot)).isTrue();
	}

	// --- Outils ------------------------------------------------------------------------------------------------

	private void receive(UUID warehouse, UUID product, int quantity) {
		as(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(warehouse, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock initial")));
	}

	private List<UUID> availabilityEvents() {
		return this.jdbc.queryForList("select object_id from integration_outbox where type = 'AVAILABILITY' order by write_id",
				UUID.class);
	}

	private static List<String> warehousesOf(JsonNode value) {
		List<String> ids = new ArrayList<>();
		for (JsonNode warehouse : value.get("perWarehouse")) {
			ids.add(warehouse.get("warehouseId").asString());
		}
		return ids;
	}

	/** Valeur servie par le flux pour le dernier événement AVAILABILITY du produit (toutes pages lues). */
	private JsonNode served(UUID product) {
		long after = 0;
		JsonNode last = null;
		Set<Long> seen = new HashSet<>();
		while (true) {
			ChangePage page = this.feed.read(LINK, after, 500, ChangeFilter.ALL);
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
