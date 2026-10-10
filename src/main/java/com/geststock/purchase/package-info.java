/**
 * Achats : commandes fournisseurs et réceptions.
 *
 * <p>Workflow : commande ({@link com.geststock.purchase.PurchaseOrderService}) → réception(s)
 * ({@link com.geststock.purchase.GoodsReceiptService}, seul point d'entrée en stock des achats,
 * dans la même transaction que les mouvements {@code RECEIPT}) → commande reçue, soldée ou
 * annulée ({@link com.geststock.purchase.PurchaseOrderStatus}).
 *
 * <p>À venir : factures fournisseurs, paiements et grand livre fournisseur (dettes),
 * valorisation au coût moyen pondéré (décision I.9), retours fournisseurs.
 */
package com.geststock.purchase;
