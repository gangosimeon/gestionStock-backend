package com.geststock.settings;

/** Modèle visuel de la facture (contrainte {@code ck_invoice_setting_template}). */
public enum InvoiceTemplate {

	/** Sobre et administratif. */
	CLASSIC,

	/** Hiérarchie visuelle marquée, bandeaux de couleur. */
	MODERN,

	/** Resserré, pour les factures de nombreuses lignes. */
	COMPACT

}
