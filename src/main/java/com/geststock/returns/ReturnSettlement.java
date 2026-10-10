package com.geststock.returns;

/** Règlement d'un retour client (de l'avoir). */
public enum ReturnSettlement {

	/** Remboursement depuis la caisse de l'utilisateur (dans la limite de ce que le client a payé). */
	REFUND,

	/** Avoir imputé sur la dette du client (dans la limite de son solde dû). */
	ACCOUNT_CREDIT

}
