package com.geststock.integration.maintenance;

import java.util.List;

/**
 * Rapport agrégé d'un passage de maintenance (G20-D11 ; plan § 33.4) : aucun identifiant ni nom d'entreprise, de liaison
 * ou de boutique, aucun secret, message d'exception, requête ni chemin. G20b : entreprises et purge ; G20c : liaisons et
 * alertes ; G20d : nettoyage.
 *
 * @param status       {@code COMPLETED} ; {@code PARTIAL} s'il reste des entreprises à visiter ou si une entreprise a
 *                     fini partiellement (budget épuisé) ; {@code FAILED} si le bail a été perdu ou si une erreur
 *                     générale a interrompu le passage
 * @param mode         mode de la purge ({@code SIMULATION} ou {@code DELETE}, G20-D5)
 * @param generation   génération du bail du passage : relie le rapport aux journaux
 * @param durationMs   durée du passage
 * @param companies    entreprises du passage
 * @param purge        purge, toutes entreprises visitées confondues
 * @param links        liaisons des entreprises mesurées par ce passage
 * @param cleanup      nettoyage de la base plateforme (G20d)
 * @param alerts       alertes du passage, regroupées par type et gravité (G20-D15) ; le workflow échoue sur une alerte
 *                     {@code CRITICAL}
 * @param authFailures échecs d'authentification vus par cette instance depuis le rapport précédent (G20-D14)
 */
public record MaintenanceReport(String status, String mode, long generation, long durationMs, Companies companies,
		Purge purge, Links links, Cleanup cleanup, List<AlertCount> alerts, AuthFailures authFailures) {

	public MaintenanceReport {
		alerts = List.copyOf(alerts);
	}

	/**
	 * @param active         entreprises actives au début du passage
	 * @param visited        entreprises commencées par ce passage (sautées comprises)
	 * @param skippedUpgrade sautées : base en migration ou en échec de migration
	 * @param errors         terminées en erreur (alerte {@code COMPANY_ERROR})
	 * @param interrupted    entreprises dont la dernière tentative avait été interrompue (deux fois de suite au moins :
	 *                       alerte {@code COMPANY_INTERRUPTED})
	 * @param remaining      entreprises actives non visitées (budget épuisé ou passage arrêté)
	 */
	public record Companies(int active, int visited, int skippedUpgrade, int errors, int interrupted, int remaining) {
	}

	/**
	 * @param eligible      événements éligibles à la purge
	 * @param deleted       événements supprimés (toujours 0 en simulation)
	 * @param batches       lots validés (simulation : lots qui seraient faits)
	 * @param blocked       entreprises dont la purge est bloquée par une liaison sans acquittement (v17-D4)
	 * @param maxLockWaitMs plus longue attente du verrou d'un lot (0 en simulation) ; alerte {@code LOCK_WAIT} au-delà
	 *                      du seuil
	 */
	public record Purge(long eligible, long deleted, long batches, int blocked, long maxLockWaitMs) {
	}

	/**
	 * @param active    liaisons actives
	 * @param suspended liaisons suspendues
	 */
	public record Links(int active, int suspended) {
	}

	/**
	 * @param expiredPendingLinks liaisons en attente expirées révoquées ({@code EXPIRED}) par ce passage (G20d)
	 */
	public record Cleanup(int expiredPendingLinks) {
	}

	/**
	 * @param type     type d'alerte (§ 33.4)
	 * @param severity {@code WARNING} ou {@code CRITICAL}
	 * @param count    occurrences dans ce passage (entreprises ou liaisons concernées)
	 */
	public record AlertCount(String type, String severity, int count) {
	}

	/**
	 * @param count échecs depuis le rapport précédent
	 * @param scope toujours {@code INSTANCE} : compteur en mémoire de l'instance qui répond
	 */
	public record AuthFailures(long count, String scope) {
	}

}
