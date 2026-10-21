package com.geststock.platform;

import java.time.Duration;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.tenancy.LegacyDatabase;

/**
 * Base plateforme : pool dédié, migrations {@code db/platform} (schéma {@code platform}) jouées à la
 * création, accès JDBC et transactions propres.
 *
 * <p>Ni la source ni son gestionnaire de transactions ne sont des beans Spring : la source routée des
 * entreprises reste l'unique {@code DataSource} de l'application (JPA, {@code JdbcTemplate}) et le
 * gestionnaire JPA reste le gestionnaire par défaut de {@code @Transactional}. Les services plateforme
 * utilisent donc {@link #inTransaction} plutôt que {@code @Transactional}.
 */
@Component
public class PlatformDb implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(PlatformDb.class);

	public static final String SCHEMA = "platform";

	private final HikariDataSource dataSource;

	private final JdbcClient jdbc;

	private final TransactionTemplate tx;

	PlatformDb(PlatformProperties properties, LegacyDatabase legacy) {
		PlatformProperties.Datasource settings = properties.datasource();
		HikariConfig config = new HikariConfig();
		config.setPoolName("platform-pool");
		if (settings.isConfigured()) {
			config.setJdbcUrl(settings.url());
			config.setUsername(settings.username());
			config.setPassword(settings.password());
		}
		else {
			// Dev/test : schéma « platform » de la base historique (refusé en production).
			config.setJdbcUrl(legacy.connection().jdbcUrl());
			config.setUsername(legacy.connection().username());
			config.setPassword(legacy.connection().password());
		}
		config.setMaximumPoolSize(Math.max(2, settings.poolSize()));
		config.setMinimumIdle(1);
		config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
		config.setIdleTimeout(Duration.ofMinutes(10).toMillis());
		config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
		config.addDataSourceProperty("ApplicationName", "geststock-platform");
		this.dataSource = new HikariDataSource(config);
		migrate(this.dataSource);
		this.jdbc = JdbcClient.create(this.dataSource);
		DataSourceTransactionManager manager = new DataSourceTransactionManager(this.dataSource);
		this.tx = new TransactionTemplate(manager);
	}

	private static void migrate(DataSource dataSource) {
		var result = Flyway.configure()
			.dataSource(dataSource)
			.schemas(SCHEMA)
			.defaultSchema(SCHEMA)
			.createSchemas(true)
			.locations("classpath:db/platform")
			.validateOnMigrate(true)
			.cleanDisabled(true)
			.load()
			.migrate();
		log.info("Base plateforme à jour ({} migration(s) appliquée(s)).", result.migrationsExecuted);
	}

	public JdbcClient jdbc() {
		return this.jdbc;
	}

	public <T> T inTransaction(TransactionCallback<T> action) {
		return this.tx.execute(action);
	}

	public void inTransaction(Runnable action) {
		this.tx.executeWithoutResult(status -> action.run());
	}

	/** Base plateforme joignable (santé de l'application). */
	public boolean isReachable() {
		try {
			return Integer.valueOf(1).equals(this.jdbc.sql("select 1").query(Integer.class).single());
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	@Override
	public void destroy() {
		this.dataSource.close();
	}

}
