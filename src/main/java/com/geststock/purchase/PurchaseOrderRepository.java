package com.geststock.purchase;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux commandes fournisseurs, réservé au module {@code purchase}. */
interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID>, JpaSpecificationExecutor<PurchaseOrder> {

	/**
	 * Commande verrouillée ({@code SELECT … FOR UPDATE}) jusqu'à la fin de la transaction :
	 * deux réceptions, ou une réception et une annulation, de la même commande se sérialisent.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from PurchaseOrder o where o.id = :id")
	Optional<PurchaseOrder> findForUpdate(@Param("id") UUID id);

	/** Prochaine valeur de la numérotation des commandes (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_purchase_order_number')", nativeQuery = true)
	long nextNumber();

}
