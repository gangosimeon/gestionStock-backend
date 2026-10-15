package com.geststock.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.customer.CustomerAccountEntryType;

/**
 * Écriture du relevé de compte client.
 *
 * @param balanceAfter solde dû (tous dépôts) après l'écriture ; {@code null} pour un utilisateur qui ne
 *                     voit pas tous les dépôts : ses variations révéleraient les opérations des autres
 * @param warehouseId  dépôt de la pièce ; {@code null} pour un historique non rattaché
 */
@Schema(name = "CustomerAccountEntry")
public record CustomerAccountEntryResponse(
		UUID id,
		UUID customerId,
		CustomerAccountEntryType type,
		CustomerAccountEntryType.Direction direction,
		BigDecimal amount,
		@Schema(description = "Solde dû (tous dépôts) après l'écriture ; absent sans warehouse:all") BigDecimal balanceAfter,
		String sourceType,
		UUID sourceId,
		String sourceReference,
		String note,
		Instant createdAt,
		UUID createdByUserId,
		@Schema(description = "Dépôt de la pièce") UUID warehouseId) {
}
