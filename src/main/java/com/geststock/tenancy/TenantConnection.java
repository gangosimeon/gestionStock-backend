package com.geststock.tenancy;

/**
 * Paramètres de connexion à la base d'une entreprise. Le mot de passe n'apparaît jamais dans
 * {@link #toString()}.
 *
 * @param jdbcUrl  URL JDBC de la base
 * @param username rôle PostgreSQL
 * @param password mot de passe (déchiffré en mémoire uniquement)
 * @param legacy   base historique (pool dimensionné différemment)
 */
public record TenantConnection(String jdbcUrl, String username, String password, boolean legacy) {

	@Override
	public String toString() {
		return "TenantConnection[jdbcUrl=" + this.jdbcUrl + ", username=" + this.username + ", password=***]";
	}

}
