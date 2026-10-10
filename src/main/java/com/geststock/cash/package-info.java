/**
 * Caisse : sessions individuelles (un caissier, un dépôt, un poste facultatif) et écritures de
 * caisse en ajout seul (ventes encaissées et remboursées, règlements clients, apports et
 * sorties d'espèces).
 *
 * <p>{@link com.geststock.cash.CashService} est le seul point d'écriture des mouvements
 * d'argent ; les ventes l'appellent pour encaisser et rembourser,
 * {@link com.geststock.cash.CustomerPaymentService} pour les règlements de dettes clients.
 * Dépendances : {@code cash} → {@code customer}, {@code warehouse}, {@code audit} ;
 * {@code sale} → {@code cash}.
 */
package com.geststock.cash;
