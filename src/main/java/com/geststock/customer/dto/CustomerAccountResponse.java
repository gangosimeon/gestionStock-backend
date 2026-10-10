package com.geststock.customer.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Situation de crédit d'un client.
 *
 * @param balance         solde dû (débits − crédits du compte)
 * @param availableCredit plafond − solde dû, au minimum 0
 */
@Schema(name = "CustomerAccount")
public record CustomerAccountResponse(
		UUID customerId,
		BigDecimal creditLimit,
		BigDecimal balance,
		BigDecimal availableCredit,
		int paymentTermsDays,
		boolean active) {
}
