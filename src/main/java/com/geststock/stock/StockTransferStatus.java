package com.geststock.stock;

/** État d'un transfert entre dépôts (contrainte {@code ck_stock_transfer_status}). */
public enum StockTransferStatus {

	/** Stock sorti du dépôt d'origine et entré dans le dépôt de destination. */
	COMPLETED,

	/** Stock revenu en totalité au dépôt d'origine. */
	RETURNED

}
