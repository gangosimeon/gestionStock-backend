package com.geststock.quote.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Corps des actions sur un devis. */
public final class QuoteActionRequests {

	private QuoteActionRequests() {
	}

	/** Annulation : motif facultatif. */
	@Schema(name = "CancelQuoteRequest")
	public record Cancel(@Size(max = 500) String reason) {
	}

	/** Facturation : vente enregistrée à la caisse à partir du devis. */
	@Schema(name = "InvoiceQuoteRequest")
	public record Invoice(@NotNull UUID saleId) {
	}

}
