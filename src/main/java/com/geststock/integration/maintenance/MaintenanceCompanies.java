package com.geststock.integration.maintenance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;
import com.geststock.tenancy.TenantRef;

/**
 * État de la maintenance par entreprise ({@code platform.integration_maintenance_company}, G20-D13 ; plan § 33.3) et
 * ordre de passage.
 *
 * <ul>
 * <li>Début d'une tentative ({@link #markStarted}) : si la précédente avait commencé sans se terminer, une interruption
 * de plus est comptée.</li>
 * <li>Fin ({@link #markCompleted}), quel que soit le résultat : compteur remis à zéro ; seul le passage qui a commencé
 * la tentative (génération) peut la terminer.</li>
 * <li>Ordre : d'abord les entreprises interrompues <b>une seule</b> fois (reprise rapide) ; puis la tentative la plus
 * ancienne, une entreprise jamais visitée en tête ; à égalité, l'ordre reçu (numéro d'entreprise). Interrompue deux
 * fois de suite : file normale, signalée ({@link CompanyTurn#repeatedlyInterrupted()}) ; une entreprise défaillante ne
 * monopolise donc jamais la tête de file.</li>
 * </ul>
 * Heures lues dans PostgreSQL ({@code clock_timestamp()}).
 */
@Component
public class MaintenanceCompanies {

	private static final String STATES = """
			select company_id, last_started_at, consecutive_interruptions,
			       (last_completed_at is null or last_completed_at < last_started_at) as incomplete
			from platform.integration_maintenance_company where company_id in (:ids)""";

	private static final String START = """
			insert into platform.integration_maintenance_company as m (company_id, last_started_at, last_started_generation)
			values (:company, clock_timestamp(), :generation)
			on conflict (company_id) do update set
			  consecutive_interruptions = case when m.last_completed_at is null or m.last_completed_at < m.last_started_at
			                                   then m.consecutive_interruptions + 1 else m.consecutive_interruptions end,
			  last_started_at = clock_timestamp(), last_started_generation = :generation""";

	private static final String COMPLETE = """
			update platform.integration_maintenance_company
			set last_completed_at = clock_timestamp(), last_outcome = :outcome, consecutive_interruptions = 0
			where company_id = :company and last_started_generation = :generation""";

	private final JdbcClient jdbc;

	MaintenanceCompanies(PlatformDb platform) {
		this.jdbc = platform.jdbc();
	}

	/** Entreprises actives reçues (dans l'ordre de leur numéro), rangées dans l'ordre de passage. */
	public List<CompanyTurn> order(List<TenantRef> active) {
		if (active.isEmpty()) {
			return List.of();
		}
		Map<UUID, State> states = new HashMap<>();
		this.jdbc.sql(STATES).param("ids", active.stream().map(TenantRef::companyId).toList())
			.query((rs, row) -> new State(rs.getObject("company_id", UUID.class), rs.getTimestamp("last_started_at").toInstant(),
					rs.getInt("consecutive_interruptions"), rs.getBoolean("incomplete")))
			.list().forEach(state -> states.put(state.companyId(), state));
		List<Ranked> ranked = new ArrayList<>(active.size());
		for (int index = 0; index < active.size(); index++) {
			TenantRef company = active.get(index);
			State state = states.get(company.companyId());
			if (state == null) {
				ranked.add(new Ranked(new CompanyTurn(company, 0, false), null, index));
				continue;
			}
			int interruptions = state.consecutiveInterruptions() + (state.incomplete() ? 1 : 0);
			boolean resumed = state.incomplete() && state.consecutiveInterruptions() == 0;
			ranked.add(new Ranked(new CompanyTurn(company, interruptions, resumed), state.lastStartedAt(), index));
		}
		ranked.sort(Comparator.comparing((Ranked r) -> !r.turn().resumed())
			.thenComparing(Ranked::lastStartedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
			.thenComparingInt(Ranked::index));
		return ranked.stream().map(Ranked::turn).toList();
	}

	/** Début d'une tentative par le passage {@code generation}, avant tout travail sur l'entreprise. */
	public void markStarted(UUID companyId, long generation) {
		this.jdbc.sql(START).param("company", companyId).param("generation", generation).update();
	}

	/**
	 * Fin de la tentative, quel que soit le résultat.
	 * @return faux si un passage plus récent a commencé l'entreprise depuis (rien n'est écrit)
	 */
	public boolean markCompleted(UUID companyId, long generation, Outcome outcome) {
		return this.jdbc.sql(COMPLETE).param("company", companyId).param("generation", generation)
			.param("outcome", outcome.name()).update() == 1;
	}

	/** Résultat d'une tentative terminée. */
	public enum Outcome {

		/** Entreprise entièrement traitée. */
		OK,
		/** Budget épuisé : reprise au passage suivant. */
		PARTIAL,
		/** Base de l'entreprise en migration ou en échec : sautée. */
		SKIPPED_UPGRADE,
		/** Erreur : les entreprises suivantes sont traitées. */
		ERROR

	}

	/**
	 * Place d'une entreprise dans l'ordre de passage.
	 * @param interruptions interruptions consécutives, la dernière tentative comprise si elle n'est pas terminée
	 * @param resumed       interrompue une seule fois : reprise en tête de file
	 */
	public record CompanyTurn(TenantRef company, int interruptions, boolean resumed) {

		/** Interrompue au moins deux fois de suite : alerte {@code COMPANY_INTERRUPTED}. */
		public boolean repeatedlyInterrupted() {
			return this.interruptions >= 2;
		}

	}

	private record State(UUID companyId, Instant lastStartedAt, int consecutiveInterruptions, boolean incomplete) {
	}

	private record Ranked(CompanyTurn turn, Instant lastStartedAt, int index) {
	}

}
