package com.geststock.sale;

/** Cycle de vie d'une vente : enregistrée, puis éventuellement annulée (jamais supprimée). */
public enum SaleStatus {

	/** Vente enregistrée : stock sorti, créance éventuelle portée au compte du client. */
	COMPLETED,

	/** Vente annulée : stock remis, créance annulée. */
	CANCELLED

}
