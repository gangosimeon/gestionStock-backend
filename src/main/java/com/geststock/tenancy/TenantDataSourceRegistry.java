package com.geststock.tenancy;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Pools de connexions des bases d'entreprise : un pool HikariCP borné par entreprise, créé à la
 * première demande, fermé après {@code idle-eviction} sans utilisation. Au-delà de {@code max-pools},
 * les pools inactifs les plus anciens sont fermés ; si aucun ne l'est, la demande est refusée
 * ({@link TenantCapacityException}) plutôt que de saturer PostgreSQL.
 *
 * <p>Un pool n'est jamais fermé tant qu'il a une connexion empruntée (transaction en cours).
 */
@Component
public class TenantDataSourceRegistry implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(TenantDataSourceRegistry.class);

	private final TenantDirectory directory;

	private final TenancyProperties.Pool settings;

	private final Clock clock;

	private final Map<UUID, Entry> pools = new ConcurrentHashMap<>();

	private final ScheduledExecutorService evictor;

	TenantDataSourceRegistry(TenantDirectory directory, TenancyProperties properties, Clock clock) {
		this.directory = directory;
		this.settings = properties.pool();
		this.clock = clock;
		this.evictor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "tenant-pool-evictor");
			thread.setDaemon(true);
			return thread;
		});
		this.evictor.scheduleWithFixedDelay(this::evictIdle, 1, 1, TimeUnit.MINUTES);
	}

	/** Pool de l'entreprise (créé au besoin). */
	public DataSource dataSource(TenantRef tenant) {
		Entry entry = this.pools.get(tenant.companyId());
		if (entry == null) {
			entry = create(tenant);
		}
		entry.touch(Instant.now(this.clock));
		return entry.dataSource;
	}

	private synchronized Entry create(TenantRef tenant) {
		Entry existing = this.pools.get(tenant.companyId());
		if (existing != null) {
			return existing;
		}
		if (this.pools.size() >= this.settings.maxPools() && !evictOldestIdle()) {
			throw new TenantCapacityException();
		}
		TenantConnection connection = this.directory.connection(tenant.companyId());
		HikariConfig config = new HikariConfig();
		config.setPoolName("tenant-" + tenant.code());
		config.setJdbcUrl(connection.jdbcUrl());
		config.setUsername(connection.username());
		config.setPassword(connection.password());
		config.setMaximumPoolSize(connection.legacy() ? this.settings.legacyMaxSize() : this.settings.maxSize());
		// Aucune connexion gardée ouverte pour une entreprise inactive.
		config.setMinimumIdle(0);
		config.setIdleTimeout(TimeUnit.MINUTES.toMillis(2));
		config.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
		config.setConnectionTimeout(this.settings.connectionTimeout().toMillis());
		config.setKeepaliveTime(TimeUnit.MINUTES.toMillis(5));
		config.addDataSourceProperty("ApplicationName", "geststock-" + tenant.code());
		config.addDataSourceProperty("tcpKeepAlive", "true");
		Entry entry = new Entry(new HikariDataSource(config), Instant.now(this.clock));
		this.pools.put(tenant.companyId(), entry);
		log.info("Pool ouvert pour l'entreprise « {} » ({} pool(s) ouvert(s)).", tenant.code(), this.pools.size());
		return entry;
	}

	/** Ferme le pool d'une entreprise (suspension, changement d'identifiants). */
	public void close(UUID companyId) {
		Entry entry = this.pools.remove(companyId);
		if (entry != null) {
			entry.dataSource.close();
		}
	}

	public int openPools() {
		return this.pools.size();
	}

	void evictIdle() {
		Instant limit = Instant.now(this.clock).minus(this.settings.idleEviction());
		this.pools.forEach((id, entry) -> {
			if (entry.lastUsed().isBefore(limit) && entry.activeConnections() == 0) {
				if (this.pools.remove(id, entry)) {
					entry.dataSource.close();
					log.debug("Pool inactif fermé ({}).", id);
				}
			}
		});
	}

	private boolean evictOldestIdle() {
		Optional<Map.Entry<UUID, Entry>> oldest = this.pools.entrySet()
			.stream()
			.filter(e -> e.getValue().activeConnections() == 0)
			.min(Comparator.comparing(e -> e.getValue().lastUsed()));
		oldest.ifPresent(e -> {
			if (this.pools.remove(e.getKey(), e.getValue())) {
				e.getValue().dataSource.close();
			}
		});
		return oldest.isPresent();
	}

	@Override
	public void destroy() {
		this.evictor.shutdownNow();
		this.pools.values().forEach(entry -> entry.dataSource.close());
		this.pools.clear();
	}

	private static final class Entry {

		private final HikariDataSource dataSource;

		private volatile Instant lastUsed;

		Entry(HikariDataSource dataSource, Instant now) {
			this.dataSource = dataSource;
			this.lastUsed = now;
		}

		void touch(Instant now) {
			this.lastUsed = now;
		}

		Instant lastUsed() {
			return this.lastUsed;
		}

		int activeConnections() {
			HikariPoolMXBean pool = this.dataSource.getHikariPoolMXBean();
			return pool != null ? pool.getActiveConnections() : 0;
		}

	}

}
