package com.geststock.integration.link;

import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.springframework.jdbc.core.SqlTypeValue;

/**
 * Texte lié à une requête sans apparaître dans les journaux : Spring JDBC journalise en TRACE la valeur de chaque
 * paramètre ({@code "Setting SQL statement parameter value"}, via {@code toString}). Pour le motif libre d'une boutique
 * (G12) et les empreintes des secrets de liaison (G14).
 *
 * @param value valeur liée
 */
record MaskedText(String value) implements SqlTypeValue {

	@Override
	public void setTypeValue(PreparedStatement statement, int index, int sqlType, String typeName) throws SQLException {
		statement.setString(index, this.value);
	}

	@Override
	public String toString() {
		return "***";
	}

}
