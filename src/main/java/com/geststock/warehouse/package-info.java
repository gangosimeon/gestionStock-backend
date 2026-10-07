/**
 * Dépôts et magasins : référentiel ({@code /api/warehouses}), dépôt principal, périmètre
 * d'accès des utilisateurs et résolution du dépôt courant
 * ({@link com.geststock.warehouse.CurrentWarehouse}, en-tête {@code X-Warehouse-Id}). Les
 * modules stock, ventes, caisse et inventaire rattachent chacune de leurs pièces au
 * {@link com.geststock.warehouse.WarehouseContext} résolu ici.
 */
package com.geststock.warehouse;
