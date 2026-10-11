package com.geststock.purchase;

import org.springframework.data.jpa.repository.JpaRepository;

/** Écriture des imputations de paiements fournisseurs, réservé au module {@code purchase}. */
interface SupplierPaymentAllocationRepository
		extends JpaRepository<SupplierPaymentAllocation, SupplierPaymentAllocation.Key> {

}
