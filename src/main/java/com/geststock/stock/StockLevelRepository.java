package com.geststock.stock;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Lecture des niveaux de stock, réservée au module {@code stock}. Volontairement
 * <b>sans méthode d'écriture</b> ({@code Repository}, pas {@code JpaRepository}) : les
 * niveaux ne sont créés et modifiés que par {@link StockService}.
 */
interface StockLevelRepository extends Repository<StockLevel, StockLevelId> {

	@Query("select l from StockLevel l where l.warehouseId = :warehouseId and l.productId in :productIds")
	List<StockLevel> findAllByWarehouse(@Param("warehouseId") UUID warehouseId,
			@Param("productIds") Collection<UUID> productIds);

}
