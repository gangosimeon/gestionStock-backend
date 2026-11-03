package com.geststock.integration;

/**
 * Résultat du séquencement de l'entreprise courante par un passage de maintenance (G20-D9).
 *
 * @param assigned événements qui ont reçu un {@code seq}
 * @param stop     {@link PassStop#LIMIT_REACHED} : le dernier lot était plein et le budget est épuisé (il reste
 *                 peut-être des événements sans {@code seq}) ; ce n'est pas une erreur
 */
public record SequencingOutcome(int assigned, PassStop stop) {
}
