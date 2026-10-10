package com.geststock.supplier.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Fournisseur. Reprend le contrat du front ({@code id, name, phone, email, address, deliveryLeadTimeDays}). */
@Schema(name = "Supplier")
public record SupplierResponse(
		UUID id,
		@Schema(example = "FOU-ESSILOR") String code,
		@Schema(example = "Essilor Distribution CI") String name,
		String contactName,
		String phone,
		String email,
		String address,
		String taxId,
		int deliveryLeadTimeDays,
		int paymentTermsDays,
		String note,
		boolean active,
		Instant createdAt,
		Instant updatedAt) {
}
