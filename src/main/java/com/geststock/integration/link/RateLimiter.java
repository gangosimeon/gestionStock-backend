package com.geststock.integration.link;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;

/**
 * Compteurs de débit en fenêtre fixe dans la base plateforme ({@code platform.integration_rate_limit}, v13-D4), pour
 * toutes les routes d'intégration : {@code /oauth/token} et {@code /oauth/par} (G9), puis flux, instantané, commandes
 * et {@code /link/secret} dans leurs étapes.
 *
 * <p>Chaque appel compte, en une seule instruction atomique ({@code INSERT … ON CONFLICT … DO UPDATE … RETURNING}),
 * avec l'horloge de PostgreSQL : début de fenêtre aligné sur un multiple de sa durée, {@code Retry-After} jusqu'à sa
 * fin (arrondi au supérieur, 1 seconde au moins). À la création d'une fenêtre, un lot borné de fenêtres anciennes est
 * supprimé ; aucune tâche planifiée (lot 3). Base inaccessible : {@link Unavailable}, jamais de laisser-passer. La clé
 * ne contient jamais de secret.
 */
@Component
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	/** Fenêtre la plus longue acceptée ({@code /link/secret} : 5 par heure). */
	public static final Duration MAX_WINDOW = Duration.ofHours(1);

	/** Lignes supprimées au plus par nettoyage. */
	static final int CLEANUP_BATCH = 100;

	private static final String HIT = """
			insert into platform.integration_rate_limit as r (counter_key, window_start, hits)
			values (:key, to_timestamp(floor(extract(epoch from now()) / :seconds) * :seconds), 1)
			on conflict (counter_key, window_start) do update set hits = least(r.hits, 2147483646) + 1
			returning r.hits, r.xmax::text = '0' as created,
			  greatest(1, ceil(extract(epoch from r.window_start - now()) + :seconds))::bigint as retry_after""";

	/** Fenêtres terminées depuis plus d'un jour (toute fenêtre dure au plus {@link #MAX_WINDOW}). */
	private static final String CLEANUP = """
			delete from platform.integration_rate_limit where ctid = any (array(
			  select ctid from platform.integration_rate_limit where window_start < now() - interval '1 day'
			  order by window_start limit :batch for update skip locked))""";

	private final JdbcClient jdbc;

	@Autowired
	RateLimiter(PlatformDb platform) {
		this(platform.jdbc());
	}

	RateLimiter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Compte un appel sous {@code key}.
	 * @throws Unavailable compteurs inaccessibles (la requête doit être refusée)
	 */
	public Decision hit(String key, int limit, Duration window) {
		long seconds = window.toSeconds();
		Counted counted;
		try {
			counted = this.jdbc.sql(HIT).param("key", key).param("seconds", seconds)
				.query((rs, row) -> new Counted(rs.getInt("hits"), rs.getBoolean("created"), rs.getLong("retry_after")))
				.single();
		}
		catch (RuntimeException ex) {
			throw new Unavailable(ex);
		}
		if (counted.created()) {
			cleanUp();
		}
		return new Decision(key, counted.hits(), limit, counted.retryAfter());
	}

	/** Nettoyage opportuniste : son échec n'annule pas le comptage déjà fait. */
	private void cleanUp() {
		try {
			int removed = this.jdbc.sql(CLEANUP).param("batch", CLEANUP_BATCH).update();
			if (removed > 0) {
				log.debug("Limitation de débit : {} fenêtre(s) ancienne(s) supprimée(s).", removed);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Limitation de débit : nettoyage des fenêtres anciennes impossible ({}).", ex.getClass().getSimpleName());
		}
	}

	private record Counted(int hits, boolean created, long retryAfter) {
	}

	/**
	 * @param key               clé du compteur
	 * @param hits              appels comptés dans la fenêtre, celui-ci compris
	 * @param limit             appels admis par fenêtre
	 * @param retryAfterSeconds secondes restantes jusqu'à la fin de la fenêtre (1 au moins)
	 */
	public record Decision(String key, int hits, int limit, long retryAfterSeconds) {

		public boolean allowed() {
			return this.hits <= this.limit;
		}

	}

	/** Compteurs inaccessibles : {@code 503 RATE_LIMIT_UNAVAILABLE}, jamais de laisser-passer (v13-D4). */
	public static final class Unavailable extends RuntimeException {

		Unavailable(Throwable cause) {
			super("Compteurs de débit inaccessibles", cause);
		}

	}

}
