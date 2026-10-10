/**
 * Retours clients et avoirs (nommé "returns" car "return" est un mot réservé Java).
 *
 * <p>Correction d'une vente <b>validée</b> sans la modifier : le retour ({@code RET-AAAA-nnnnnn})
 * s'y rattache, remet en stock les articles revendables et rembourse le client (caisse) ou lui
 * accorde un avoir sur son compte. L'annulation complète d'une vente reste dans le module
 * {@code sale} ; les corrections de caisse (contre-passation) dans le module {@code cash}.
 * Les retours fournisseurs ne sont pas implémentés.
 */
package com.geststock.returns;
