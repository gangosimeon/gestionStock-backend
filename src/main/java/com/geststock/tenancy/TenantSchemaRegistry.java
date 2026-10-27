package com.geststock.tenancy;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * État du schéma de chaque base d'entreprise depuis le démarrage de cette instance, tenu par la mise à
 * jour en arrière-plan des bases.
 *
 * <p>Une entreprise dont la base est <b>en cours de mise à jour</b> ou dont la mise à jour a
 * <b>échoué</b> est refusée (503) : le code déployé ne doit jamais tourner sur un schéma en retard.
 * Une base pas encore vérifiée reste accessible : la vérification est rapide et, le plus souvent, aucune
 * migration n'est à appliquer (aucune interruption à chaque démarrage).
 *
 * <p>État en mémoire, propre à l'instance : chaque instance vérifie les bases à son démarrage.
 */
@Component
public class TenantSchemaRegistry {

	/** État du schéma d'une base. */
	public enum State {
		/** Migrations en attente détectées, application en cours. */
		UPGRADING,
		/** Schéma à jour (aucune migration en attente, ou toutes appliquées). */
		UP_TO_DATE,
		/** Vérification ou migration impossible : base refusée jusqu'à une nouvelle tentative réussie. */
		FAILED
	}

	/**
	 * @param pending  migrations en attente détectées lors de la dernière vérification
	 * @param applied  migrations appliquées lors de la dernière mise à jour
	 * @param message  cause d'un échec (sans secret), sinon {@code null}
	 */
	public record Entry(UUID companyId, String code, State state, int pending, int applied, String message,
			Instant updatedAt) {
	}

	private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

	private final Clock clock;

	public TenantSchemaRegistry(Clock clock) {
		this.clock = clock;
	}

	public void upgrading(TenantRef tenant, int pending) {
		put(tenant, State.UPGRADING, pending, 0, null);
	}

	public void upToDate(TenantRef tenant, int pending, int applied) {
		put(tenant, State.UP_TO_DATE, pending, applied, null);
	}

	public void failed(TenantRef tenant, int pending, String message) {
		put(tenant, State.FAILED, pending, 0, message);
	}

	/** État qui interdit l'accès à l'entreprise (mise à jour en cours ou en échec), s'il y en a un. */
	public Optional<State> blockingState(UUID companyId) {
		Entry entry = this.entries.get(companyId);
		return entry == null || entry.state() == State.UP_TO_DATE ? Optional.empty() : Optional.of(entry.state());
	}

	/** Toutes les bases vérifiées par cette instance, par code d'entreprise. */
	public List<Entry> entries() {
		return this.entries.values().stream().sorted(Comparator.comparing(Entry::code)).toList();
	}

	private void put(TenantRef tenant, State state, int pending, int applied, String message) {
		this.entries.put(tenant.companyId(), new Entry(tenant.companyId(), tenant.code(), state, pending, applied, message,
				Instant.now(this.clock)));
	}

}
