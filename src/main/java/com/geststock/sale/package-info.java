/**
 * Ventes : tarification serveur, sortie de stock, crédit client, numérotation, annulation.
 *
 * <p>{@link com.geststock.sale.SaleService} enregistre une vente dans une seule transaction
 * (stock via {@code StockService}, créance via {@code CustomerAccountService}, audit via
 * {@code AuditService}). À venir : encaissement en caisse (module {@code cash}), retours.
 */
package com.geststock.sale;
