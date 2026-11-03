package com.geststock.integration;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.springframework.jdbc.core.SqlTypeValue;

/**
 * Valeur reçue liée à une requête sans apparaître dans les journaux : Spring JDBC journalise en TRACE la valeur de
 * chaque paramètre ({@code "Setting SQL statement parameter value"}, via {@code toString}). Comme {@code MaskedText}
 * des liaisons (G12, G14), pour les valeurs reçues des prix proposés (G22a : prix, note, clé d'idempotence, réponse
 * enregistrée) ; texte ou décimal.
 *
 * @param value valeur liée, {@link String} ou {@link BigDecimal}
 */
record MaskedValue(Object value) implements SqlTypeValue {

	@Override
	public void setTypeValue(PreparedStatement statement, int index, int sqlType, String typeName) throws SQLException {
		if (this.value instanceof BigDecimal decimal) {
			statement.setBigDecimal(index, decimal);
		}
		else {
			statement.setString(index, (String) this.value);
		}
	}

	/** Valeur masquée ; {@code null} reste {@code null}. */
	static Object of(Object value) {
		return value != null ? new MaskedValue(value) : null;
	}

	@Override
	public String toString() {
		return "***";
	}

}
