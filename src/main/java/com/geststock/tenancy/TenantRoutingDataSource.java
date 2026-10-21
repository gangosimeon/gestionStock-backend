package com.geststock.tenancy;

import java.sql.Connection;
import java.sql.SQLException;

import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Source de données de toute l'application métier (JPA, {@code JdbcTemplate}, Flyway métier) : chaque
 * connexion est prise dans le pool de l'entreprise courante ({@link TenantContext}).
 *
 * <p>Sans entreprise courante : entreprise par défaut si elle est configurée (dev/test uniquement), sinon
 * {@link TenantContextMissingException}. Il n'existe aucune base de repli en production.
 */
public class TenantRoutingDataSource extends AbstractDataSource {

	private final TenantDataSourceRegistry registry;

	private final DefaultTenant defaultTenant;

	public TenantRoutingDataSource(TenantDataSourceRegistry registry, DefaultTenant defaultTenant) {
		this.registry = registry;
		this.defaultTenant = defaultTenant;
	}

	@Override
	public Connection getConnection() throws SQLException {
		TenantRef tenant = TenantContext.current().or(this.defaultTenant::get).orElseThrow(TenantContextMissingException::new);
		return this.registry.dataSource(tenant).getConnection();
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		throw new SQLException("Identifiants imposés par l'annuaire des entreprises.");
	}

}
