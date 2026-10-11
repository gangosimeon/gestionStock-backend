package com.geststock.customer;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Vue d'un client pour les autres modules (ventes, paiements, retours) : ce qu'une pièce doit
 * recopier ou appliquer (nom affiché, tarif par défaut, conditions de crédit), sans exposer
 * l'entité. La dette se lit par {@link CustomerAccountService#creditStatus}.
 */
public record CustomerSnapshot(UUID id, String code, String name, CustomerType type, PriceCategory priceCategory,
		BigDecimal creditLimit, int paymentTermsDays, boolean active, String phone) {

	static CustomerSnapshot of(Customer customer) {
		return new CustomerSnapshot(customer.getId(), customer.getCode(), customer.getName(), customer.getType(),
				customer.getPriceCategory(), customer.getCreditLimit(), customer.getPaymentTermsDays(),
				customer.isActive(), customer.getPhone());
	}

}
