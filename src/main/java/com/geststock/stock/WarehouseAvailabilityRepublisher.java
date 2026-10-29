package com.geststock.stock;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.geststock.integration.IntegrationEvents;
import com.geststock.warehouse.event.WarehouseActivationChangedEvent;

/**
 * Disponibilité republiée quand un dépôt change d'état : sans mouvement de stock, le périmètre des dépôts actifs
 * change, donc la disponibilité de chaque produit qui a un niveau de stock dans ce dépôt. Écoute synchrone : même
 * transaction que le changement d'état (annulé avec lui), une requête puis une insertion groupée.
 */
@Component
class WarehouseAvailabilityRepublisher {

	static final String STOCKED_PRODUCTS = """
			select product_id from stock_level where warehouse_id = :warehouseId""";

	private final JdbcClient jdbc;

	private final IntegrationEvents integration;

	WarehouseAvailabilityRepublisher(DataSource dataSource, IntegrationEvents integration) {
		this.jdbc = JdbcClient.create(dataSource);
		this.integration = integration;
	}

	@EventListener
	void onWarehouseActivationChanged(WarehouseActivationChangedEvent event) {
		List<UUID> products = this.jdbc.sql(STOCKED_PRODUCTS)
			.param("warehouseId", event.warehouseId())
			.query(UUID.class)
			.list();
		this.integration.publishAvailability(products);
	}

}
