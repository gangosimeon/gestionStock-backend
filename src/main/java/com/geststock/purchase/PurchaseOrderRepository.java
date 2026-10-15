package com.geststock.purchase;

import java.util.Collection;
import java.util.List;
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

	/**
	 * Commandes d'un fournisseur non annulées dans un dépôt, verrouillées, des plus anciennes aux
	 * plus récentes (ordre d'imputation d'un paiement global, et ordre constant des verrous).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select o from PurchaseOrder o
			where o.supplierId = :supplierId and o.warehouseId = :warehouseId
			  and o.status <> com.geststock.purchase.PurchaseOrderStatus.CANCELLED
			order by o.createdAt, o.id""")
	List<PurchaseOrder> findPayableForUpdate(@Param("supplierId") UUID supplierId,
			@Param("warehouseId") UUID warehouseId);

	/**
	 * Totaux d'un fournisseur : {@code [commandé (hors annulées), reçu, dû, payé, commandes avec
	 * reste à payer]}, sur tous les dépôts ou ceux donnés. Le dû suit
	 * {@link PurchaseOrder#payableAmount()}.
	 */
	@Query("""
			select coalesce(sum(case when o.status <> com.geststock.purchase.PurchaseOrderStatus.CANCELLED
			                         then o.totalAmount else 0 end), 0),
			       coalesce(sum(o.receivedAmount), 0),
			       coalesce(sum(case when o.status = com.geststock.purchase.PurchaseOrderStatus.CLOSED then o.receivedAmount
			                         when o.status = com.geststock.purchase.PurchaseOrderStatus.CANCELLED then 0
			                         else o.totalAmount end), 0),
			       coalesce(sum(o.paidAmount), 0),
			       coalesce(sum(case when (case when o.status = com.geststock.purchase.PurchaseOrderStatus.CLOSED
			                                         then o.receivedAmount
			                                    when o.status = com.geststock.purchase.PurchaseOrderStatus.CANCELLED then 0
			                                    else o.totalAmount end) > o.paidAmount then 1 else 0 end), 0)
			from PurchaseOrder o
			where o.supplierId = :supplierId and (:allWarehouses = true or o.warehouseId in :warehouseIds)""")
	List<Object[]> accountTotals(@Param("supplierId") UUID supplierId, @Param("allWarehouses") boolean allWarehouses,
			@Param("warehouseIds") Collection<UUID> warehouseIds);

	/** {@code true} si le fournisseur a déjà une facture de ce numéro (sans tenir compte de la casse). */
	@Query("""
			select count(o) > 0 from PurchaseOrder o
			where o.supplierId = :supplierId and lower(o.invoiceNumber) = lower(:number)""")
	boolean existsInvoiceNumber(@Param("supplierId") UUID supplierId, @Param("number") String number);

	/** Prochaine valeur de la numérotation des commandes (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_purchase_order_number')", nativeQuery = true)
	long nextNumber();

}
