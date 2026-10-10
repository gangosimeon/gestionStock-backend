package com.geststock.supplier;

import java.util.UUID;

/**
 * Vue d'un fournisseur pour les autres modules (produits, achats) : ce qu'une pièce d'achat
 * doit recopier ou calculer (nom affiché, date de livraison prévue, échéance de facture),
 * sans exposer l'entité.
 */
public record SupplierSnapshot(UUID id, String code, String name, int deliveryLeadTimeDays, int paymentTermsDays,
		boolean active) {

	static SupplierSnapshot of(Supplier supplier) {
		return new SupplierSnapshot(supplier.getId(), supplier.getCode(), supplier.getName(),
				supplier.getDeliveryLeadTimeDays(), supplier.getPaymentTermsDays(), supplier.isActive());
	}

}
