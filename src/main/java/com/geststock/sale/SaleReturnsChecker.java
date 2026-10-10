package com.geststock.sale;

import java.util.UUID;

/**
 * Indique si une vente a fait l'objet de retours, fourni par le module {@code returns}.
 *
 * <p>Inversion de dépendance : {@code returns} dépend de {@code sale}, pas l'inverse. Une vente
 * ayant des retours ne peut plus être annulée (la base l'impose aussi).
 */
public interface SaleReturnsChecker {

	boolean hasReturns(UUID saleId);

}
