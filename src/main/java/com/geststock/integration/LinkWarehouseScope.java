package com.geststock.integration;

import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Périmètre de disponibilité d'une liaison (lot 2, étape G4a ; v11-D8) : ses dépôts ({@code integration_link_warehouse})
 * encore actifs. Remplace le périmètre du lot 1 (tous les dépôts actifs) : une liaison sans dépôt n'expose aucune
 * disponibilité, jamais « tous les dépôts » par défaut. Lu dans la transaction de lecture du flux ou de l'instantané
 * (même instantané que la page) ; un dépôt désactivé sort du périmètre à la page suivante.
 */
@Component
class LinkWarehouseScope implements WarehouseScope {

	private static final String SCOPE = """
			select lw.warehouse_id from integration_link_warehouse lw
			join warehouse w on w.id = lw.warehouse_id and w.active
			where lw.link_id = :link""";

	private final JdbcClient jdbc;

	LinkWarehouseScope(DataSource dataSource) {
		this.jdbc = JdbcClient.create(dataSource);
	}

	@Override
	public Set<UUID> warehouseIds(LinkRef link) {
		return Set.copyOf(this.jdbc.sql(SCOPE).param("link", link.id()).query(UUID.class).list());
	}

}
