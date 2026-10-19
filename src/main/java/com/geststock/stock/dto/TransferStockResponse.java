package com.geststock.stock.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Résultat d'un transfert ; {@code ok}, {@code out} et {@code in} conservent le contrat du front.
 *
 * @param transferId identifiant de la pièce ({@code transfer.id})
 * @param out        sortie de la première ligne (contrat d'origine) ; toutes : {@code movements}
 * @param in         entrée de la première ligne (contrat d'origine)
 * @param movements  tous les mouvements, par ligne : sortie puis entrée
 * @param transfer   pièce enregistrée (reçu)
 */
@Schema(name = "TransferStockResponse")
public record TransferStockResponse(
		boolean ok,
		UUID transferId,
		StockMovementResponse out,
		@Schema(name = "in") StockMovementResponse in,
		List<StockMovementResponse> movements,
		StockTransferResponse transfer) {
}
