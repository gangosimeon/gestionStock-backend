package com.geststock.stock.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Résultat d'un transfert ; {@code ok} conserve le contrat du front. */
@Schema(name = "TransferStockResponse")
public record TransferStockResponse(
		boolean ok,
		UUID transferId,
		StockMovementResponse out,
		@Schema(name = "in") StockMovementResponse in) {
}
