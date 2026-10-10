package com.geststock.report.dto;

import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Stock en fin de journée, reconstitué depuis le journal des mouvements (pas d'estimation).
 *
 * @param label     date courte {@code JJ/MM} (axe du graphique du front)
 * @param stockTotal quantités en stock à la fin du jour (à l'instant présent pour aujourd'hui)
 */
@Schema(name = "DashboardStockDay")
public record StockDayPoint(LocalDate date, String label, long stockTotal) {
}
