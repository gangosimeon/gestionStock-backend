package com.geststock.integration;

/**
 * Résultat d'un passage de la purge.
 *
 * @param mode                simulation ou suppression
 * @param ceilingSeq          plafond {@code P} : {@code last_seq} lu au début du passage, avant la liste des liaisons
 *                            (v17-D11)
 * @param floor               limite des acquittements de l'entreprise (aucune liaison, bloquée, ou plus petit
 *                            acquittement)
 * @param purgedThroughBefore repère de purge avant le passage
 * @param eligibleThroughSeq  borne {@code B} : plus grand {@code seq} éligible (égal au repère si rien n'est éligible),
 *                            jamais au-delà de {@code ceilingSeq}
 * @param eligibleCount       événements éligibles ({@code repère < seq ≤ B})
 * @param batches             lots validés (simulation : lots qui seraient faits, dans la limite du passage)
 * @param deletedCount        événements supprimés (toujours 0 en simulation)
 * @param purgedThroughAfter  repère de purge après le passage (inchangé en simulation)
 * @param stop                raison de l'arrêt : {@link PassStop#LIMIT_REACHED} s'il reste des événements éligibles au-delà
 *                            des limites du passage (lots, durée, budget) ; {@link PassStop#LEASE_LOST} si le bail de la
 *                            maintenance a été perdu (G20b)
 * @param maxLockWaitMs       plus longue attente du verrou de la ligne d'état du flux par un lot, en millisecondes
 *                            (G20c ; 0 en simulation, qui ne prend aucun verrou)
 */
public record PurgeReport(PurgeMode mode, long ceilingSeq, AckFloor floor, long purgedThroughBefore,
		long eligibleThroughSeq, long eligibleCount, int batches, long deletedCount, long purgedThroughAfter,
		PassStop stop, long maxLockWaitMs) {
}
