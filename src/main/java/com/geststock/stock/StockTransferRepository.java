package com.geststock.stock;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux pièces de transfert, réservé au module {@code stock}. */
interface StockTransferRepository extends JpaRepository<StockTransfer, UUID>, JpaSpecificationExecutor<StockTransfer> {

	/** Transfert verrouillé jusqu'à la fin de la transaction : deux retours concurrents se sérialisent. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from StockTransfer t where t.id = :id")
	Optional<StockTransfer> findForUpdate(@Param("id") UUID id);

	/** Prochaine valeur de la numérotation des transferts (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_stock_transfer_number')", nativeQuery = true)
	long nextNumber();

}
