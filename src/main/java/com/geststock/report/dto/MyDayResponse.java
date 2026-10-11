package com.geststock.report.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Journée du vendeur connecté (tableau de bord caissier) : ses propres ventes du jour, tous
 * dépôts confondus, calculées par PostgreSQL. Ni marge, ni chiffres du magasin.
 *
 * @param salesCount     ventes validées du jour
 * @param salesTotal     montant de ces ventes
 * @param paidAmount     part encaissée au moment des ventes (tous moyens de paiement)
 * @param creditAmount   crédit accordé : part des ventes du jour portée au compte des clients, figée
 *                       à la vente (un règlement ultérieur ne la modifie pas)
 * @param customerPaymentsAmount règlements de dettes clients encaissés aujourd'hui par le vendeur,
 *                       quelle que soit la date des ventes réglées
 * @param customerPaymentsCount  nombre de ces règlements
 * @param collectedAmount encaissé aujourd'hui : {@code paidAmount + customerPaymentsAmount}
 * @param cancelledCount ventes du vendeur annulées aujourd'hui
 * @param last7Days      ses ventes validées des 7 derniers jours (aujourd'hui compris), jour par jour
 */
@Schema(name = "MyDay")
public record MyDayResponse(
		LocalDate date,
		long salesCount,
		BigDecimal salesTotal,
		BigDecimal paidAmount,
		BigDecimal creditAmount,
		BigDecimal customerPaymentsAmount,
		long customerPaymentsCount,
		BigDecimal collectedAmount,
		long cancelledCount,
		Instant lastSaleAt,
		java.util.List<SellerDayPoint> last7Days) {

	/** Ventes du vendeur un jour donné (0 sans vente). */
	@Schema(name = "SellerDayPoint")
	public record SellerDayPoint(LocalDate date, String label, long count, BigDecimal total) {
	}

}
