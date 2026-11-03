package com.geststock.integration.maintenance;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;

/**
 * Bail unique de la maintenance ({@code platform.integration_maintenance_lease}, G20-D4, G20-D12) : jeton de clôture
 * (génération croissante) et propriétaire tiré à chaque prise. Chaque opération est une seule instruction, validée
 * seule, sur l'horloge de PostgreSQL ({@code clock_timestamp()}), jamais celle de l'application.
 *
 * <ul>
 * <li>Prise : seulement si le bail est libre, libéré ou échu ; deux prises simultanées : la seconde attend la première
 * puis réévalue la condition (READ COMMITTED), une seule réussit.</li>
 * <li>Vérification, prolongation, libération : seulement par la génération <b>et</b> le propriétaire courants ; un bail
 * échu n'est jamais prolongé ; un ancien propriétaire ne peut rien sur le bail d'un passage plus récent.</li>
 * </ul>
 * Le bail n'évite que le travail en double : la purge reste sûre sans lui (verrou de l'état du flux et plafond {@code P}
 * de chaque lot, G17).
 */
@Component
public class MaintenanceLease {

	private static final String ACQUIRE = """
			update platform.integration_maintenance_lease
			set generation = generation + 1, owner = :owner, acquired_at = clock_timestamp(),
			    expires_at = clock_timestamp() + make_interval(secs => :seconds), released_at = null
			where id = 1 and (owner is null or released_at is not null or expires_at <= clock_timestamp())
			returning generation""";

	private static final String HELD = """
			select count(*) from platform.integration_maintenance_lease
			where id = 1 and generation = :generation and owner = :owner and released_at is null
			  and expires_at > clock_timestamp()""";

	private static final String EXTEND = """
			update platform.integration_maintenance_lease set expires_at = clock_timestamp() + make_interval(secs => :seconds)
			where id = 1 and generation = :generation and owner = :owner and released_at is null
			  and expires_at > clock_timestamp()""";

	private static final String RELEASE = """
			update platform.integration_maintenance_lease set released_at = clock_timestamp()
			where id = 1 and generation = :generation and owner = :owner and released_at is null""";

	private final JdbcClient jdbc;

	MaintenanceLease(PlatformDb platform) {
		this.jdbc = platform.jdbc();
	}

	/** Bail pris pour {@code ttl}, ou vide s'il est tenu par un autre passage (non échu). */
	public Optional<Lease> acquire(Duration ttl) {
		UUID owner = UUID.randomUUID();
		return this.jdbc.sql(ACQUIRE).param("owner", owner).param("seconds", seconds(ttl)).query(Long.class).optional()
			.map(generation -> new Lease(generation, owner));
	}

	/** Bail toujours tenu par ce passage, non échu : à vérifier avant chaque entreprise et chaque lot. */
	public boolean isHeld(Lease lease) {
		return this.jdbc.sql(HELD).param("generation", lease.generation()).param("owner", lease.owner())
			.query(Long.class).single() == 1;
	}

	/** Échéance repoussée à {@code ttl} à partir de maintenant ; refusé pour un bail échu ou perdu. */
	public boolean extend(Lease lease, Duration ttl) {
		return this.jdbc.sql(EXTEND).param("generation", lease.generation()).param("owner", lease.owner())
			.param("seconds", seconds(ttl)).update() == 1;
	}

	/** Libération par son propriétaire ; sans effet sur le bail d'un passage plus récent ou déjà libéré. */
	public boolean release(Lease lease) {
		return this.jdbc.sql(RELEASE).param("generation", lease.generation()).param("owner", lease.owner()).update() == 1;
	}

	private static double seconds(Duration ttl) {
		return ttl.toMillis() / 1000.0;
	}

	/**
	 * @param generation jeton de clôture du passage (relie aussi le rapport aux journaux)
	 * @param owner      identifiant aléatoire du passage
	 */
	public record Lease(long generation, UUID owner) {
	}

}
