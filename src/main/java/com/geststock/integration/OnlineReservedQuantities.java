package com.geststock.integration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Quantites reservees par les commandes en ligne actives (statut RESERVED, non expirees) :
 * soustraites du stock pour calculer la disponibilite (G27, G28). Remplace {@code NoReservedQuantities}
 * du lot 1 (aucune reservation).
 *
 * <p>La requete est executee dans la transaction de lecture du flux ou de la route de disponibilite
 * (meme instantane que la page) ; elle lit {@code online_reservation_line} jointe a
 * {@code online_reservation} pour les reservations actives ({@code status = 'RESERVED'} et
 * {@code expires_at > now()}).
 */
@Component
class OnlineReservedQuantities implements ReservedQuantities {

	private static final String RESERVED = """
			select orl.product_id, orl.warehouse_id, sum(orl.quantity) as reserved
			from online_reservation_line orl
			join online_reservation or_ on or_.id = orl.reservation_id
			where orl.product_id in (:productIds)
			  and orl.warehouse_id in (:warehouseIds)
			  and or_.status = 'RESERVED'
			  and or_.expires_at > now()
			group by orl.product_id, orl.warehouse_id""";

	private final JdbcClient jdbc;

	OnlineReservedQuantities(DataSource dataSource) {
		this.jdbc = JdbcClient.create(dataSource);
	}

	@Override
	public Map<UUID, Map<UUID, Integer>> reserved(Set<UUID> productIds, Set<UUID> warehouseIds) {
		if (productIds.isEmpty() || warehouseIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, Map<UUID, Integer>> result = new HashMap<>();
		this.jdbc.sql(RESERVED)
			.param("productIds", productIds)
			.param("warehouseIds", warehouseIds)
			.query((rs, n) -> {
				UUID productId = rs.getObject("product_id", UUID.class);
				UUID warehouseId = rs.getObject("warehouse_id", UUID.class);
				int reserved = rs.getInt("reserved");
				result.computeIfAbsent(productId, id -> new HashMap<>()).put(warehouseId, reserved);
				return null;
			})
			.list();
		return result;
	}

}
