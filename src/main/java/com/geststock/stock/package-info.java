/**
 * Stock : source de vérité unique de la quantité disponible ({@link com.geststock.stock.StockLevel},
 * une ligne par dépôt × produit) et journal immuable des mouvements
 * ({@link com.geststock.stock.StockMovement}). Toute écriture passe par
 * {@link com.geststock.stock.StockService#applyMovements(java.util.List)}.
 */
package com.geststock.stock;
