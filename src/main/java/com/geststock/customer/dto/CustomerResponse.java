package com.geststock.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.customer.CustomerType;
import com.geststock.customer.PriceCategory;

/**
 * Client. Reprend le contrat du front ({@code id, name, phone, email, creditLimit}).
 *
 * @param balance         solde dû (créances), lu dans le compte client
 * @param availableCredit crédit encore utilisable : plafond − solde dû, au minimum 0
 */
@Schema(name = "Customer")
public record CustomerResponse(
		UUID id,
		@Schema(example = "CLI-000042") String code,
		CustomerType type,
		String name,
		String phone,
		String email,
		String address,
		String city,
		String taxId,
		PriceCategory priceCategory,
		BigDecimal creditLimit,
		int paymentTermsDays,
		String note,
		boolean active,
		BigDecimal balance,
		BigDecimal availableCredit,
		Instant createdAt,
		Instant updatedAt) {
}
