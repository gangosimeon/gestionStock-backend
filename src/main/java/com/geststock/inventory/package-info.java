/**
 * Inventaires physiques : périmètre (complet, catégorie, sélection), comptage avec quantité
 * théorique relevée par le serveur, écarts, validation ({@code inventory:validate}) appliquant
 * les écarts au stock par {@code StockService} en une transaction. Workflow :
 * {@link com.geststock.inventory.InventoryStatus}.
 */
package com.geststock.inventory;
