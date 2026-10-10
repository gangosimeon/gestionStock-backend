/**
 * Alertes du tableau de bord, <b>calculées à la demande</b> à partir des données existantes :
 * stock (rupture, seuil minimum, stock faible, surstock, produit inactif en stock, stock
 * dormant), commandes fournisseurs en retard, créances clients échues et plafonds de crédit,
 * caisses (ouverture prolongée, écarts de clôture) et inventaires à valider.
 *
 * <p>Aucune table d'alertes ni notification externe : seuls les seuils sont stockés
 * ({@code alert_setting}, V021), modifiables à chaud avec {@code alert:configure}.
 */
package com.geststock.alert;
