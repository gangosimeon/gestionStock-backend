package com.geststock.report.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Indicateurs d'une période (dates incluses, fuseau de l'entreprise).
 *
 * @param warehouseId dépôt couvert ({@code null} : tous les dépôts)
 */
@Schema(name = "DashboardSummary")
public record DashboardSummaryResponse(Instant generatedAt, UUID warehouseId, LocalDate from, LocalDate to,
		SalesKpis sales, PurchaseKpis purchases, StockKpis stock) {
}
