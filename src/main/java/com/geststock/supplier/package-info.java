/**
 * Fournisseurs : référentiel (coordonnées, délais de livraison et de paiement, statut).
 *
 * <p>Relations avec les achats : les autres modules obtiennent un fournisseur par
 * {@link com.geststock.supplier.SupplierService#getForAssignment} (actif exigé pour une
 * nouvelle pièce) sous forme de {@link com.geststock.supplier.SupplierSnapshot}. Commandes,
 * réceptions, factures, paiements et grand livre fournisseur (dettes) relèvent du module
 * {@code purchase}, qui servira aussi {@code /api/suppliers/{id}/purchases|payments} ; le
 * module fournisseur ne dépend jamais des achats.
 */
package com.geststock.supplier;
