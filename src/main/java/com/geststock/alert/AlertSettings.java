package com.geststock.alert;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Seuils des alertes. 0 désactive l'alerte concernée (sauf délai de paiement et tolérance).
 *
 * @param defaultMinStock            seuil minimum des produits sans seuil propre ({@code alertThreshold = 0})
 * @param lowStockMarginPercent      « stock faible » jusqu'à seuil minimum + ce pourcentage du seuil
 * @param dormantStockDays           « stock dormant » sans sortie depuis ce nombre de jours
 * @param purchaseOverdueGraceDays   tolérance après la date de livraison prévue d'une commande
 * @param defaultPaymentTermsDays    délai de paiement des clients sans délai propre
 * @param creditLimitWarningPercent  alerte dès ce pourcentage du plafond de crédit utilisé
 * @param cashSessionMaxOpenHours    caisse ouverte depuis plus de ce nombre d'heures
 * @param cashDifferenceTolerance    écart de clôture toléré (FCFA, valeur absolue)
 * @param cashDifferenceLookbackDays écarts signalés sur les clôtures de ce nombre de jours
 */
@Schema(name = "AlertSettings")
public record AlertSettings(
		@NotNull @Min(0) @Max(100_000) @Schema(example = "0") Integer defaultMinStock,
		@NotNull @Min(0) @Max(1000) @Schema(example = "50") Integer lowStockMarginPercent,
		@NotNull @Min(0) @Max(3650) @Schema(example = "90") Integer dormantStockDays,
		@NotNull @Min(0) @Max(365) @Schema(example = "0") Integer purchaseOverdueGraceDays,
		@NotNull @Min(0) @Max(365) @Schema(example = "30") Integer defaultPaymentTermsDays,
		@NotNull @Min(0) @Max(100) @Schema(example = "90") Integer creditLimitWarningPercent,
		@NotNull @Min(0) @Max(720) @Schema(example = "24") Integer cashSessionMaxOpenHours,
		@NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) @Schema(example = "1000") BigDecimal cashDifferenceTolerance,
		@NotNull @Min(0) @Max(365) @Schema(example = "7") Integer cashDifferenceLookbackDays) {
}
