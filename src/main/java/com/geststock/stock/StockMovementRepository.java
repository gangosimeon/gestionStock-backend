package com.geststock.stock;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/**
 * Journal des mouvements, réservé au module {@code stock}. Ajout seul : ni mise à jour ni
 * suppression (refusées aussi en base).
 */
interface StockMovementRepository extends Repository<StockMovement, UUID> {

	StockMovement save(StockMovement movement);

	Optional<StockMovement> findById(UUID id);

	boolean existsByReversalOfId(UUID reversalOfId);

}
