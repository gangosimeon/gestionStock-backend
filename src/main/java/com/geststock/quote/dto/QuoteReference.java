package com.geststock.quote.dto;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Informations d'un devis reprises sur la facture de la vente qui en est issue. Champs
 * facultatifs non renseignés : {@code null}.
 */
@Schema(name = "QuoteReference")
public record QuoteReference(
		UUID id,
		String number,
		LocalDate quoteDate,
		String subject,
		String lotReference,
		Integer beneficiaries,
		String deliveryAddress,
		String contractReference,
		String deliveryTerms,
		String paymentTerms,
		String executionDelay,
		String notes) {
}
