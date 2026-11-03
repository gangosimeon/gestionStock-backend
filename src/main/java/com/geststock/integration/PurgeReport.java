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
 */
public record PurgeReport(PurgeMode mode, long ceilingSeq, AckFloor floor, long purgedThroughBefore,
		long eligibleThroughSeq, long eligibleCount, int batches, long deletedCount, long purgedThroughAfter) {
}
