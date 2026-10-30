package com.geststock.integration;

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

import tools.jackson.databind.JsonNode;

/**
 * Disponibilité servie par liaison (lot 2, étape G4a ; v11-D8) : dépôts de la liaison ∩ dépôts actifs, au flux comme à
 * l'instantané. Deux liaisons de la même entreprise voient chacune le stock de leurs dépôts ; une liaison sans dépôt
 * ne voit aucun stock (jamais « tous les dépôts » par défaut) ; un dépôt désactivé sort du périmètre à la page
 * suivante.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class LinkWarehouseAvailabilityIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	@Autowired
	private StockService stock;

	@Autowired
	private ChangeFeedService feed;

	@Autowired
	private SnapshotService snapshots;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private BusinessScenario scenario;

	private UUID actor;

	private UUID store;

	private UUID depot;

	private final List<UUID> links = new ArrayList<>();

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.scenario = new BusinessScenario(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("lien-dispo");
		this.store = this.fixtures.warehouse("lien-magasin");
		this.depot = this.fixtures.warehouse("lien-depot");
		this.jdbc.update("update warehouse set type = 'WAREHOUSE' where id = ?", this.depot);
		resetFeed();
	}

	@AfterEach
	void tearDown() {
		for (UUID link : this.links) {
			this.jdbc.update("delete from integration_link_warehouse where link_id = ?", link);
		}
		this.scenario.purge(List.of(this.store, this.depot), List.of(), List.of());
		this.fixtures.purge();
		resetFeed();
	}

	@Test
	void twoLinksOfTheSameCompanySeeTheStockOfTheirOwnWarehouses() {
		UUID product = this.fixtures.product("lien", 1000);
		receive(this.store, product, 3);
		receive(this.depot, product, 5);
		LinkRef storeOnly = link(this.store);
		LinkRef depotOnly = link(this.depot);
		LinkRef both = link(this.store, this.depot);

		assertAvailability(storeOnly, product, 3, Set.of(this.store));
		assertAvailability(depotOnly, product, 5, Set.of(this.depot));
		assertAvailability(both, product, 8, Set.of(this.store, this.depot));
	}

	@Test
	void aLinkWithoutWarehousesSeesNoStock() {
		UUID product = this.fixtures.product("lien-vide", 1000);
		receive(this.store, product, 4);
		assertAvailability(link(), product, 0, Set.of());
	}

	@Test
	void aDeactivatedWarehouseOfTheLinkIsExcludedImmediately() {
		UUID product = this.fixtures.product("lien-ferme", 1000);
		receive(this.store, product, 3);
		receive(this.depot, product, 5);
		LinkRef both = link(this.store, this.depot);
		assertAvailability(both, product, 8, Set.of(this.store, this.depot));

		this.jdbc.update("update warehouse set active = false where id = ?", this.depot);
		receive(this.store, product, 1);
		assertAvailability(both, product, 4, Set.of(this.store));
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	/** Même valeur à l'instantané et au flux (dernier événement AVAILABILITY du produit). */
	private void assertAvailability(LinkRef link, UUID product, int available, Set<UUID> warehouses) {
		for (JsonNode value : List.of(fromSnapshot(link, product), fromFeed(link, product))) {
			assertThat(value.get("available").asInt()).as("disponibilité de la liaison %s", link.id()).isEqualTo(available);
			Set<UUID> served = new HashSet<>();
			value.get("perWarehouse").forEach(entry -> served.add(UUID.fromString(entry.get("warehouseId").asString())));
			assertThat(served).as("dépôts servis à la liaison %s", link.id()).isEqualTo(warehouses);
		}
	}

	private JsonNode fromSnapshot(LinkRef link, UUID product) {
		String token = null;
		do {
			SnapshotPage page = this.snapshots.read(link, token, 500);
			for (JsonNode value : page.availability()) {
				if (value.get("productId").asString().equals(product.toString())) {
					return value;
				}
			}
			token = page.nextPageToken();
		}
		while (token != null);
		throw new AssertionError("disponibilité absente de l'instantané pour " + product);
	}

	private JsonNode fromFeed(LinkRef link, UUID product) {
		long after = 0;
		JsonNode last = null;
		while (true) {
			ChangePage page = this.feed.read(link, after, 500, ChangeFilter.ALL);
			for (ChangeEvent event : page.items()) {
				if (event.type() == IntegrationEventType.AVAILABILITY && event.objectId().equals(product)) {
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

	private LinkRef link(UUID... warehouses) {
		UUID id = UUID.randomUUID();
		this.links.add(id);
		for (UUID warehouse : warehouses) {
			this.jdbc.update("insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (?, ?, ?)", id,
					warehouse, this.actor);
		}
		return new LinkRef(id);
	}

	private void receive(UUID warehouse, UUID product, int quantity) {
		StockFixtures.runAs(this.actor, () -> this.stock.applyMovement(StockMovementCommand.of(warehouse, product,
				StockMovementType.RECEIPT, quantity, StockSourceType.MANUAL, "Stock de la liaison")));
	}

	private void resetFeed() {
		this.jdbc.update("delete from integration_outbox");
		this.jdbc.update("""
				insert into integration_feed_state (id) values (?::uuid)
				on conflict (id) do update set last_seq = 0, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
