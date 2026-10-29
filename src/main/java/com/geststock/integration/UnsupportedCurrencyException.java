package com.geststock.integration;

/**
 * Devise de l'entreprise sans correspondance ISO 4217 connue (décision v11-D6) : l'instantané n'est pas servi, et
 * la création d'une liaison échouera de même (lot 2). Corriger {@code platform.company.currency}, ou étendre la
 * table de correspondance par une décision.
 */
public class UnsupportedCurrencyException extends IllegalStateException {

	public UnsupportedCurrencyException(String stored) {
		super("Devise de l'entreprise « " + stored + " » sans code ISO 4217 connu (valeurs reconnues : FCFA, F CFA, XOF) : "
				+ "corriger la devise de l'entreprise.");
	}

}
