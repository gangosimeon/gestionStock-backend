package com.geststock.integration.maintenance;

/**
 * Rapport agrégé d'un passage de maintenance (G20-D11 ; plan § 33.4) : aucun identifiant ni nom d'entreprise, de liaison
 * ou de boutique, aucun secret, message d'exception, requête ni chemin. G20b : entreprises et purge ; les liaisons, le
 * nettoyage et les alertes viennent avec G20c et G20d.
 *
 * @param status       {@code COMPLETED} ; {@code PARTIAL} s'il reste des entreprises à visiter ou si une entreprise a
 *                     fini partiellement (budget épuisé) ; {@code FAILED} si le bail a été perdu ou si une erreur
 *                     générale a interrompu le passage
 * @param mode         mode de la purge ({@code SIMULATION} ou {@code DELETE}, G20-D5)
 * @param generation   génération du bail du passage : relie le rapport aux journaux
 * @param durationMs   durée du passage
 * @param companies    entreprises du passage
 * @param purge        purge, toutes entreprises visitées confondues
 * @param authFailures échecs d'authentification vus par cette instance depuis le rapport précédent (G20-D14)
 */
public record MaintenanceReport(String status, String mode, long generation, long durationMs, Companies companies,
		Purge purge, AuthFailures authFailures) {

	/**
	 * @param active         entreprises actives au début du passage
	 * @param visited        entreprises commencées par ce passage (sautées comprises)
	 * @param skippedUpgrade sautées : base en migration ou en échec de migration
	 * @param errors         terminées en erreur (alertes en G20c)
	 * @param interrupted    entreprises dont la dernière tentative avait été interrompue (alertes en G20c)
	 * @param remaining      entreprises actives non visitées (budget épuisé ou passage arrêté)
	 */
	public record Companies(int active, int visited, int skippedUpgrade, int errors, int interrupted, int remaining) {
	}

	/**
	 * @param eligible événements éligibles à la purge
	 * @param deleted  événements supprimés (toujours 0 en simulation)
	 * @param batches  lots validés (simulation : lots qui seraient faits)
	 * @param blocked  entreprises dont la purge est bloquée par une liaison sans acquittement (v17-D4)
	 */
	public record Purge(long eligible, long deleted, long batches, int blocked) {
	}

	/**
	 * @param count échecs depuis le rapport précédent
	 * @param scope toujours {@code INSTANCE} : compteur en mémoire de l'instance qui répond
	 */
	public record AuthFailures(long count, String scope) {
	}

}
