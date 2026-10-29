package com.geststock.warehouse;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.geststock.integration.WarehouseScope;

/**
 * Périmètre de disponibilité du lot 1 : tous les dépôts actifs de l'entreprise, quel que soit leur type
 * ({@link WarehouseType#STORE} ou {@link WarehouseType#WAREHOUSE}). Lu dans la transaction de lecture du flux.
 */
@Component
class ActiveWarehouseScope implements WarehouseScope {

	private final WarehouseRepository warehouses;

	ActiveWarehouseScope(WarehouseRepository warehouses) {
		this.warehouses = warehouses;
	}

	@Override
	public Set<UUID> warehouseIds() {
		return this.warehouses.findAllByActiveTrueOrderByNameAsc().stream().map(Warehouse::getId)
			.collect(Collectors.toUnmodifiableSet());
	}

}
