package com.geststock.quote;

/**
 * Statut affiché et filtrable : le statut enregistré, sauf un devis émis dont la date de
 * validité est passée, présenté « expiré » (déduit, jamais enregistré).
 */
public enum QuoteDisplayStatus {

	DRAFT, ISSUED, ACCEPTED, REFUSED, EXPIRED, INVOICED, CANCELLED

}
