package com.geststock.warehouse;

import java.util.UUID;

/**
 * Dépôt dans lequel s'exécute la requête, résolu et contrôlé par le serveur (voir
 * {@link CurrentWarehouse}). Toute opération de stock ou de vente s'y rattache.
 */
public record WarehouseContext(UUID id, String code, String name, WarehouseType type, boolean main) {

	static WarehouseContext of(Warehouse warehouse) {
		return new WarehouseContext(warehouse.getId(), warehouse.getCode(), warehouse.getName(), warehouse.getType(),
				warehouse.isMain());
	}

}
