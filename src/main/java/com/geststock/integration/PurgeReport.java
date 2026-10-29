package com.geststock.integration;

/**
 * Résultat d'un passage de la purge.
 *
 * @param mode                simulation ou suppression
 * @param purgedThroughBefore repère de purge avant le passage
 * @param eligibleThroughSeq  plus grand {@code seq} éligible (égal au repère si rien n'est éligible)
 * @param eligibleCount       événements éligibles
 * @param deletedCount        événements supprimés (toujours 0 en simulation)
 * @param purgedThroughAfter  repère de purge après le passage (inchangé en simulation)
 */
public record PurgeReport(PurgeMode mode, long purgedThroughBefore, long eligibleThroughSeq, long eligibleCount,
		long deletedCount, long purgedThroughAfter) {
}
