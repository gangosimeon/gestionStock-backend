package com.geststock.inventory;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux inventaires, réservé au module {@code inventory}. */
interface InventorySessionRepository
		extends JpaRepository<InventorySession, UUID>, JpaSpecificationExecutor<InventorySession> {

	/** Inventaire non terminé dans le dépôt (au plus un : index {@code uk_inventory_session_open_warehouse}). */
	@Query("""
			select count(s) > 0 from InventorySession s where s.warehouseId = :warehouseId
			  and s.status not in (com.geststock.inventory.InventoryStatus.COMPLETED,
			                       com.geststock.inventory.InventoryStatus.CANCELLED)""")
	boolean existsOpenInWarehouse(@Param("warehouseId") UUID warehouseId);

	/** Prochaine valeur de la numérotation des inventaires. */
	@Query(value = "select nextval('seq_inventory_number')", nativeQuery = true)
	long nextNumber();

}
