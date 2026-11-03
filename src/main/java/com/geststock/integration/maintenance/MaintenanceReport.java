package com.geststock.integration.maintenance;

/**
 * Rapport agrégé d'un passage de maintenance (G20-D11 ; plan § 33.4) : aucun identifiant ni nom d'entreprise, de liaison
 * ou de boutique, aucun secret, message d'exception, requête ni chemin. G20a : sans travail ; les sections des
 * entreprises, de la purge, des liaisons et des alertes viennent avec G20b à G20d.
 *
 * @param status       {@code COMPLETED}
 * @param mode         mode de la purge ({@code SIMULATION} ou {@code DELETE}, G20-D5)
 * @param generation   génération du bail du passage : relie le rapport aux journaux
 * @param durationMs   durée du passage
 * @param authFailures échecs d'authentification vus par cette instance depuis le rapport précédent (G20-D14)
 */
public record MaintenanceReport(String status, String mode, long generation, long durationMs, AuthFailures authFailures) {

	/**
	 * @param count échecs depuis le rapport précédent
	 * @param scope toujours {@code INSTANCE} : compteur en mémoire de l'instance qui répond
	 */
	public record AuthFailures(long count, String scope) {
	}

}
