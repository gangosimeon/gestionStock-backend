package com.geststock.quote.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.quote.QuoteDisplayStatus;
import com.geststock.quote.QuoteStatus;

/**
 * Devis. Les champs facultatifs non renseignés valent {@code null} (jamais une chaîne vide).
 *
 * @param status        statut enregistré
 * @param displayStatus statut affiché : {@code EXPIRED} pour un devis émis dont la validité est passée
 * @param saleId        vente (facture) issue du devis, une fois facturé
 */
@Schema(name = "Quote")
public record QuoteResponse(
		UUID id,
		@Schema(example = "DEV-2026-000001") String number,
		UUID warehouseId,
		UUID customerId,
		String customerName,
		LocalDate quoteDate,
		LocalDate validUntil,
		String subject,
		String lotReference,
		Integer beneficiaries,
		String deliveryAddress,
		String contractReference,
		String deliveryTerms,
		String paymentTerms,
		String executionDelay,
		String notes,
		QuoteStatus status,
		QuoteDisplayStatus displayStatus,
		List<LineResponse> lines,
		BigDecimal total,
		UUID saleId,
		String saleNumber,
		Instant invoicedAt,
		Instant cancelledAt,
		String cancellationReason,
		Instant createdAt,
		Instant updatedAt) {

	@Schema(name = "QuoteLine")
	public record LineResponse(UUID id, int lineNumber, UUID productId, String productSku, String productName,
			int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
	}

}
