package com.geststock.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.geststock.report.dto.ReportFilter;

/**
 * Contexte d'exécution d'un rapport : critères du client, période résolue et dépôt couvert
 * ({@code null} : tous les dépôts, droit vérifié par le contrôleur).
 */
record ReportScope(ReportFilter filter, ReportPeriod period, UUID warehouseId) {

	/** Nom lisible d'un utilisateur (alias {@code u} de {@code app_user}). */
	static final String USER_NAME = "btrim(coalesce(u.first_name || ' ', '') || coalesce(u.last_name, u.username))";

	static BigDecimal money(ResultSet rs, String column) throws SQLException {
		BigDecimal value = rs.getBigDecimal(column);
		return (value != null ? value : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
	}

	static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value != null ? value.toInstant() : null;
	}

	static UUID uuid(ResultSet rs, String column) throws SQLException {
		return rs.getObject(column, UUID.class);
	}

	/** Pourcentage {@code part / whole × 100} à une décimale, {@code null} si {@code whole ≤ 0}. */
	static BigDecimal rate(BigDecimal part, BigDecimal whole) {
		return whole.signum() > 0 ? part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP) : null;
	}

}
