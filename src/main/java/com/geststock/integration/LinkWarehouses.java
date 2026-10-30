package com.geststock.integration;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.exception.InvalidRequestException;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Dépôts vendables de chaque liaison, dans la base de l'entreprise ({@code integration_link_warehouse}, lot 2, étape
 * G4a ; v11-D8, v14-D10) : choisis à l'approbation et remplacés à la réactivation, parmi les dépôts actifs accessibles
 * à la personne connectée (même règle que le reste de GestStock : {@link WarehouseAccess}, affectations ou
 * {@code warehouse:all}). STORE et WAREHOUSE sont acceptés ; « STORE seulement par défaut » est une règle de l'écran.
 */
@Component
public class LinkWarehouses {

	private static final String COLUMNS = "w.id, w.code, w.name, w.type, w.active";

	private static final String ACTIVE = "select " + COLUMNS + " from warehouse w where w.active order by w.name, w.code";

	private static final String BY_ID = "select " + COLUMNS + " from warehouse w where w.id = :id";

	private static final String OF_LINKS = """
			select lw.link_id, %s from integration_link_warehouse lw join warehouse w on w.id = lw.warehouse_id
			where lw.link_id in (:links) order by w.name, w.code""".formatted(COLUMNS);

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final WarehouseAccess access;

	private final IntegrationEvents events;

	LinkWarehouses(DataSource dataSource, PlatformTransactionManager transactionManager, WarehouseAccess access,
			IntegrationEvents events) {
		this.jdbc = JdbcClient.create(dataSource);
		this.transaction = new TransactionTemplate(transactionManager);
		this.access = access;
		this.events = events;
	}

	/** Dépôts actifs accessibles à la personne connectée, avec leur type (écran de consentement). */
	public List<WarehouseChoice> accessible() {
		Set<UUID> visible = this.access.visibleWarehouseIds();
		return this.jdbc.sql(ACTIVE).query(LinkWarehouses::choice).list().stream()
			.filter(warehouse -> visible == null || visible.contains(warehouse.id()))
			.toList();
	}

	/**
	 * Chaque dépôt doit exister dans l'entreprise, être accessible à la personne connectée et actif ; refus explicite
	 * sinon, comme pour le choix du dépôt courant : inconnu (ou d'une autre entreprise) {@code 400 UNKNOWN_WAREHOUSE},
	 * hors périmètre {@code 403 WAREHOUSE_ACCESS_DENIED} (état non révélé), désactivé {@code 403 WAREHOUSE_INACTIVE}.
	 */
	public void requireAccessible(Set<UUID> warehouseIds) {
		Set<UUID> visible = this.access.visibleWarehouseIds();
		for (UUID id : warehouseIds) {
			WarehouseChoice warehouse = this.jdbc.sql(BY_ID).param("id", id).query(LinkWarehouses::choice).optional()
				.orElseThrow(() -> new InvalidRequestException(ErrorCode.UNKNOWN_WAREHOUSE,
						ErrorCode.UNKNOWN_WAREHOUSE.defaultMessage()));
			if (visible != null && !visible.contains(id)) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
						ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
			}
			if (!warehouse.active()) {
				throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_INACTIVE, ErrorCode.WAREHOUSE_INACTIVE.defaultMessage());
			}
		}
	}

	/**
	 * Approbation : dépôts de la nouvelle liaison, en une transaction de la base de l'entreprise ; aucun événement (la
	 * liaison commencera par un instantané).
	 */
	public void replace(UUID linkId, Set<UUID> warehouseIds, UUID addedBy) {
		this.transaction.executeWithoutResult(status -> write(linkId, warehouseIds, addedBy));
	}

	private void write(UUID linkId, Set<UUID> warehouseIds, UUID addedBy) {
		this.jdbc.sql("delete from integration_link_warehouse where link_id = :link").param("link", linkId).update();
		for (UUID warehouseId : warehouseIds) {
			this.jdbc.sql("""
					insert into integration_link_warehouse (link_id, warehouse_id, added_by) values (:link, :warehouse, :by)""")
				.param("link", linkId).param("warehouse", warehouseId).param("by", addedBy).update();
		}
	}

	/**
	 * Réactivation : remplace les dépôts de la liaison et, si la liste change, republie la disponibilité (v11-D9) :
	 * un événement {@code AVAILABILITY} mince par produit qui a un niveau de stock dans l'ancienne ou la nouvelle liste,
	 * dans la <b>même transaction</b> que le remplacement (tout ou rien). Liste identique : périmètre inchangé, aucun
	 * événement ; les lignes sont réécrites au nom de la personne qui reconfirme.
	 */
	public void reconfirm(UUID linkId, Set<UUID> warehouseIds, UUID addedBy) {
		this.transaction.executeWithoutResult(status -> {
			Set<UUID> previous = Set.copyOf(this.jdbc.sql("select warehouse_id from integration_link_warehouse where link_id = :link")
				.param("link", linkId).query(UUID.class).list());
			write(linkId, warehouseIds, addedBy);
			if (!previous.equals(warehouseIds)) {
				Set<UUID> union = new HashSet<>(previous);
				union.addAll(warehouseIds);
				republish(union);
			}
		});
	}

	/**
	 * Événement {@code AVAILABILITY} mince pour chaque produit qui a un niveau de stock dans ces dépôts, en une seule
	 * insertion ({@link IntegrationEvents#publishAvailability}) ; dans la transaction de l'appelant.
	 */
	public void republish(Set<UUID> warehouseIds) {
		if (warehouseIds.isEmpty()) {
			return;
		}
		List<UUID> products = this.jdbc.sql("select distinct product_id from stock_level where warehouse_id in (:warehouses)")
			.param("warehouses", warehouseIds).query(UUID.class).list();
		this.events.publishAvailability(products);
	}

	/** Retire tous les dépôts de la liaison (compensation d'une approbation qui n'a pas abouti). */
	public void remove(UUID linkId) {
		this.jdbc.sql("delete from integration_link_warehouse where link_id = :link").param("link", linkId).update();
	}

	/** Dépôts de chaque liaison (actifs ou non, pour l'affichage) ; une liaison absente n'en a aucun. */
	public Map<UUID, List<WarehouseChoice>> ofLinks(Collection<UUID> linkIds) {
		Map<UUID, List<WarehouseChoice>> byLink = new HashMap<>();
		if (linkIds.isEmpty()) {
			return byLink;
		}
		this.jdbc.sql(OF_LINKS).param("links", linkIds)
			.query((rs, n) -> byLink.computeIfAbsent((UUID) rs.getObject("link_id"), id -> new java.util.ArrayList<>())
				.add(choice(rs, n)))
			.list();
		return byLink;
	}

	private static WarehouseChoice choice(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new WarehouseChoice((UUID) rs.getObject("id"), rs.getString("code"), rs.getString("name"), rs.getString("type"),
				rs.getBoolean("active"));
	}

	/**
	 * Dépôt proposé ou retenu pour une liaison.
	 *
	 * @param type {@code STORE} (magasin) ou {@code WAREHOUSE} (entrepôt)
	 */
	@Schema(name = "IntegrationLinkWarehouse")
	public record WarehouseChoice(UUID id, String code, String name, String type, boolean active) {
	}

}
