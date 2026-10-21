package com.geststock.platform.company;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.geststock.platform.CredentialCipher;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.PlatformProperties;
import com.geststock.tenancy.JdbcUrls;
import com.geststock.tenancy.LegacyDatabase;
import com.geststock.tenancy.TenantConnection;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;

/**
 * Annuaire des entreprises (base plateforme). Statuts mis en cache 30 s pour ne pas interroger la
 * plateforme à chaque requête ; toute modification de statut appelle {@link #invalidate}.
 */
@Component
public class CompanyDirectory implements TenantDirectory {

	static final Duration CACHE_TTL = Duration.ofSeconds(30);

	private final PlatformDb db;

	private final CredentialCipher cipher;

	private final LegacyDatabase legacy;

	private final PlatformProperties properties;

	private final Clock clock;

	private final Map<UUID, Cached> byId = new ConcurrentHashMap<>();

	CompanyDirectory(PlatformDb db, CredentialCipher cipher, LegacyDatabase legacy, PlatformProperties properties,
			Clock clock) {
		this.db = db;
		this.cipher = cipher;
		this.legacy = legacy;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public Optional<TenantStatus> findByCode(String code) {
		if (code == null || code.isBlank()) {
			return Optional.empty();
		}
		String normalized = code.trim().toLowerCase(Locale.ROOT);
		Optional<TenantStatus> cached = this.byId.values()
			.stream()
			.filter(c -> c.isFresh(now()) && c.status.ref().code().equals(normalized))
			.map(c -> c.status)
			.findFirst();
		if (cached.isPresent()) {
			return cached;
		}
		return this.db.jdbc()
			.sql("select id, code, status, trade_name from platform.company where code = :code")
			.param("code", normalized)
			.query((rs, n) -> new TenantStatus(new TenantRef(rs.getObject("id", UUID.class), rs.getString("code")),
					rs.getString("status"), rs.getString("trade_name")))
			.optional()
			.map(this::remember);
	}

	@Override
	public Optional<TenantStatus> findById(UUID companyId) {
		Cached cached = this.byId.get(companyId);
		if (cached != null && cached.isFresh(now())) {
			return Optional.of(cached.status);
		}
		return this.db.jdbc()
			.sql("select id, code, status, trade_name from platform.company where id = :id")
			.param("id", companyId)
			.query((rs, n) -> new TenantStatus(new TenantRef(rs.getObject("id", UUID.class), rs.getString("code")),
					rs.getString("status"), rs.getString("trade_name")))
			.optional()
			.map(this::remember);
	}

	@Override
	public TenantConnection connection(UUID companyId) {
		return this.db.jdbc()
			.sql("select mode, database_name, runtime_role, runtime_password from platform.company_database where company_id = :id")
			.param("id", companyId)
			.query((rs, n) -> {
				if ("LEGACY".equals(rs.getString("mode"))) {
					return this.legacy.connection();
				}
				String url = JdbcUrls.withDatabase(serverUrl(), rs.getString("database_name"));
				return new TenantConnection(url, rs.getString("runtime_role"), this.cipher.decrypt(rs.getString("runtime_password")),
						false);
			})
			.optional()
			.orElseThrow(() -> new IllegalStateException("Aucune base enregistrée pour l'entreprise " + companyId));
	}

	@Override
	public List<TenantRef> activeTenants() {
		return this.db.jdbc()
			.sql("select c.id, c.code from platform.company c join platform.company_database d on d.company_id = c.id "
					+ "where c.status = 'ACTIVE' order by c.number")
			.query((rs, n) -> new TenantRef(rs.getObject("id", UUID.class), rs.getString("code")))
			.list();
	}

	/** Serveur PostgreSQL des bases dédiées : celui du compte de provisionnement (sinon la base historique). */
	public String serverUrl() {
		return this.properties.provisioner().isConfigured() ? this.properties.provisioner().url() : this.legacy.url();
	}

	public void invalidate(UUID companyId) {
		this.byId.remove(companyId);
	}

	private TenantStatus remember(TenantStatus status) {
		this.byId.put(status.ref().companyId(), new Cached(status, now()));
		return status;
	}

	private Instant now() {
		return Instant.now(this.clock);
	}

	private record Cached(TenantStatus status, Instant loadedAt) {

		boolean isFresh(Instant now) {
			return this.loadedAt.plus(CACHE_TTL).isAfter(now);
		}

	}

}
