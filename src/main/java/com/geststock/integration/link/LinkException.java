package com.geststock.integration.link;

/**
 * Refus métier d'une opération sur une liaison. Le message nomme la raison, jamais un secret ni une empreinte. La
 * correspondance avec les codes d'erreur de l'API viendra avec les routes (étapes G8 à G11).
 */
public class LinkException extends RuntimeException {

	/** Raison du refus. */
	public enum Reason {

		/** Demande d'autorisation inconnue. */
		REQUEST_NOT_FOUND("demande d'autorisation introuvable"),
		/** Demande d'autorisation sans décision d'approbation. */
		REQUEST_NOT_APPROVED("demande d'autorisation non approuvée"),
		/** Client désactivé. */
		CLIENT_DISABLED("client JeCréeMaBoutique désactivé"),
		/** Entreprise inconnue. */
		COMPANY_NOT_FOUND("entreprise introuvable"),
		/** Entreprise qui n'est pas ACTIVE (en attente, en cours de création, en échec, suspendue). */
		COMPANY_NOT_ACTIVE("entreprise non active"),
		/** Devise de l'entreprise sans code ISO 4217 connu (v11-D6). */
		CURRENCY_UNSUPPORTED("devise de l'entreprise sans code ISO 4217 connu"),
		/** Portée accordée absente de la demande. */
		SCOPE_NOT_REQUESTED("portée non demandée"),
		/** Portée demandée que le client n'est pas autorisé à obtenir. */
		SCOPE_NOT_ALLOWED("portée non autorisée pour ce client"),
		/** Argument absent ou mal formé. */
		INVALID_ARGUMENT("paramètre invalide"),
		/** La boutique a déjà une liaison non révoquée pour ce client (v14-D2). */
		SHOP_ALREADY_LINKED("boutique déjà liée"),
		/** Liaison inconnue. */
		LINK_NOT_FOUND("liaison introuvable"),
		/** Transition refusée depuis le statut courant. */
		INVALID_TRANSITION("transition invalide");

		private final String label;

		Reason(String label) {
			this.label = label;
		}

	}

	private final Reason reason;

	public LinkException(Reason reason) {
		this(reason, null);
	}

	public LinkException(Reason reason, String detail) {
		super("Liaison refusée : " + reason.label + (detail != null ? " (" + detail + ")" : ""));
		this.reason = reason;
	}

	public Reason reason() {
		return this.reason;
	}

}
