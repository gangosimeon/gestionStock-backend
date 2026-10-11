package com.geststock.purchase;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux paiements fournisseurs et à leurs imputations, réservé au module {@code purchase}. */
interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, UUID> {

	Page<SupplierPayment> findBySupplierId(UUID supplierId, Pageable pageable);

	/** Imputations des paiements donnés, avec le numéro de la commande. */
	@Query("""
			select a.id.paymentId, a.id.purchaseOrderId, o.number, a.amount
			from SupplierPaymentAllocation a, PurchaseOrder o
			where o.id = a.id.purchaseOrderId and a.id.paymentId in :paymentIds
			order by o.createdAt, o.id""")
	List<Object[]> allocations(@Param("paymentIds") Collection<UUID> paymentIds);

	/** Prochaine valeur de la numérotation des paiements (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_supplier_payment_number')", nativeQuery = true)
	long nextNumber();

}
