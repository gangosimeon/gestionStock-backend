package com.geststock.stock;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.geststock.integration.AvailabilityProvider;
import com.geststock.integration.ReservedQuantities;
import com.geststock.integration.WarehouseScope;

/**
 * Disponibilité servie dans les événements {@code AVAILABILITY} (schéma {@code Availability} du contrat v10) :
 * pour chaque produit, stock des dépôts du périmètre ({@link WarehouseScope}) moins les réservations
 * ({@link ReservedQuantities}), jamais négatif, avec le détail par dépôt ({@code perWarehouse}, décision C8).
 *
 * <p>Lu dans la transaction de lecture du flux (même instantané que la page), en <b>une seule requête</b> sur
 * {@code stock_level} pour tous les produits de la page. Le détail ne liste que les dépôts du périmètre qui ont
 * un niveau de stock pour le produit (un dépôt absent vaut 0) ; le total est la somme du détail. Quantités
 * entières : {@code stock_level.quantity} est un entier, quelle que soit l'unité de vente du produit.
 */
@Component
class StockAvailabilityProvider implements AvailabilityProvider {

	static final String LEVELS = """
			select product_id, warehouse_id, quantity
			from stock_level
			where product_id in (:productIds) and warehouse_id in (:warehouseIds)""";

	private final JdbcClient jdbc;

	private final WarehouseScope scope;

	private final ReservedQuantities reserved;

	private final Clock clock;

	StockAvailabilityProvider(DataSource dataSource, WarehouseScope scope, ReservedQuantities reserved, Clock clock) {
		this.jdbc = JdbcClient.create(dataSource);
		this.scope = scope;
		this.reserved = reserved;
		this.clock = clock;
	}

	@Override
	public Map<UUID, Availability> availability(Set<UUID> productIds) {
		if (productIds.isEmpty()) {
			return Map.of();
		}
		Set<UUID> warehouseIds = this.scope.warehouseIds();
		Map<UUID, Map<UUID, Integer>> stock = new HashMap<>();
		Map<UUID, Map<UUID, Integer>> reservations = Map.of();
		if (!warehouseIds.isEmpty()) {
			this.jdbc.sql(LEVELS)
				.param("productIds", productIds)
				.param("warehouseIds", warehouseIds)
				.query((rs, row) -> stock
					.computeIfAbsent(rs.getObject("product_id", UUID.class), id -> new TreeMap<>())
					.put(rs.getObject("warehouse_id", UUID.class), rs.getInt("quantity")))
				.list();
			reservations = this.reserved.reserved(productIds, warehouseIds);
		}
		Instant computedAt = Instant.now(this.clock);
		Map<UUID, Availability> values = new HashMap<>();
		for (UUID productId : productIds) {
			Map<UUID, Integer> reservedHere = reservations.getOrDefault(productId, Map.of());
			List<WarehouseAvailability> perWarehouse = new ArrayList<>();
			int total = 0;
			for (Map.Entry<UUID, Integer> level : stock.getOrDefault(productId, Map.of()).entrySet()) {
				int available = Math.max(0, level.getValue() - reservedHere.getOrDefault(level.getKey(), 0));
				perWarehouse.add(new WarehouseAvailability(level.getKey(), available));
				total = Math.addExact(total, available);
			}
			values.put(productId, new Availability(productId, total, List.copyOf(perWarehouse), computedAt));
		}
		return values;
	}

	/** Schéma {@code Availability} du contrat. */
	record Availability(UUID productId, int available, List<WarehouseAvailability> perWarehouse, Instant computedAt) {
	}

	/** Élément de {@code Availability.perWarehouse}. */
	record WarehouseAvailability(UUID warehouseId, int available) {
	}

}
