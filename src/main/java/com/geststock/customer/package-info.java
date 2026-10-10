/**
 * Clients : référentiel (identité, coordonnées, conditions commerciales, statut) et compte
 * client (créances, plafond de crédit).
 *
 * <p>Relations avec les autres modules, qui dépendent de celui-ci (jamais l'inverse) :
 * <ul>
 * <li>ventes : {@link com.geststock.customer.CustomerService#getForAssignment} (client actif,
 * tarif par défaut) puis {@link com.geststock.customer.CustomerAccountService#recordCreditSale}
 * pour le reste dû d'une vente à crédit ; annulation par {@code SALE_CANCELLATION} ;</li>
 * <li>paiements : {@code recordCredit(PAYMENT)}, l'encaissement en caisse relevant du module
 * caisse ;</li>
 * <li>retours : {@code recordCredit(CREDIT_NOTE)} pour imputer un avoir sur la dette.</li>
 * </ul>
 * Aucune comptabilité : seul le solde dû par le client est suivi.
 */
package com.geststock.customer;
