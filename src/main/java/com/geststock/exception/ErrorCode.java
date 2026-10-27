package com.geststock.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Codes d'erreur stables de l'API. Chaque code porte son statut HTTP et un message
 * français par défaut, affiché tel quel par le front.
 *
 * <p>Les modules métier ajouteront ici leurs propres codes (ex. {@code STOCK_INSUFFICIENT}).
 * Un code publié ne doit jamais être renommé : les clients peuvent s'en servir.
 */
public enum ErrorCode {

	// 400
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Données invalides."),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Requête illisible : vérifiez le format des données envoyées."),
	INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Paramètre invalide."),

	// 401 / 403
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentification requise."),
	ACCESS_DENIED(HttpStatus.FORBIDDEN, "Accès refusé."),

	// 404 / 405 / 406 / 415
	RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Ressource introuvable."),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Méthode HTTP non autorisée pour cette ressource."),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Format de réponse demandé non disponible."),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Format de données non supporté."),

	// 409
	CONFLICT(HttpStatus.CONFLICT, "Opération impossible dans l'état actuel de la ressource."),
	CONCURRENT_MODIFICATION(HttpStatus.CONFLICT,
			"La ressource a été modifiée par un autre utilisateur. Veuillez recharger puis réessayer."),
	DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "Opération impossible : elle violerait l'intégrité des données."),

	// 422
	BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "Opération refusée par une règle de gestion."),

	// --- Authentification ----------------------------------------------------------------
	// Messages volontairement génériques : ils ne révèlent pas si l'identifiant existe.
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Identifiant ou mot de passe incorrect."),
	ACCOUNT_LOCKED(HttpStatus.UNAUTHORIZED,
			"Compte temporairement verrouillé après plusieurs échecs de connexion. Réessayez plus tard."),
	ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "Ce compte est désactivé. Contactez un administrateur."),
	INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Session expirée ou invalide. Veuillez vous reconnecter."),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Session expirée. Veuillez vous reconnecter."),
	CURRENT_PASSWORD_INVALID(HttpStatus.BAD_REQUEST, "Le mot de passe actuel est incorrect."),

	// --- Entreprises et plateforme -----------------------------------------------------
	COMPANY_REQUIRED(HttpStatus.BAD_REQUEST, "Indiquez le code de votre entreprise."),
	COMPANY_SUSPENDED(HttpStatus.FORBIDDEN,
			"L'accès de votre entreprise est suspendu. Contactez le support GestStock pour le rétablir."),
	COMPANY_UNAVAILABLE(HttpStatus.FORBIDDEN, "L'espace de votre entreprise n'est pas encore disponible."),
	SERVICE_CAPACITY_REACHED(HttpStatus.SERVICE_UNAVAILABLE,
			"Service momentanément saturé. Réessayez dans quelques instants."),
	SCHEMA_UPGRADE_IN_PROGRESS(HttpStatus.SERVICE_UNAVAILABLE,
			"Mise à jour de vos données en cours. Réessayez dans quelques instants."),
	SCHEMA_UPGRADE_FAILED(HttpStatus.SERVICE_UNAVAILABLE,
			"Mise à jour de vos données momentanément impossible. L'équipe GestStock est prévenue : réessayez plus tard."),
	COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, "Entreprise introuvable."),
	COMPANY_CODE_ALREADY_USED(HttpStatus.CONFLICT, "Ce code d'entreprise est déjà utilisé."),
	COMPANY_INVALID_TRANSITION(HttpStatus.CONFLICT, "Opération impossible dans l'état actuel de l'entreprise."),
	PROVISIONING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
			"Création des bases indisponible : configuration de provisionnement incomplète."),
	BRANDING_INVALID_LOGO(HttpStatus.BAD_REQUEST, "Logo refusé : image PNG ou JPEG de 1 Mo au plus."),
	BRANDING_NOT_EDITABLE(HttpStatus.FORBIDDEN, "La personnalisation de votre entreprise est gérée par GestStock."),
	ACTIVATION_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "Lien d'activation invalide, déjà utilisé ou expiré."),

	// --- Codes métier ------------------------------------------------------------------

	// Pagination / tri
	UNKNOWN_SORT_FIELD(HttpStatus.BAD_REQUEST, "Critère de tri non autorisé."),

	// Utilisateurs
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Utilisateur introuvable."),
	USERNAME_ALREADY_USED(HttpStatus.CONFLICT, "Ce nom d'utilisateur est déjà utilisé."),
	EMAIL_ALREADY_USED(HttpStatus.CONFLICT, "Cette adresse email est déjà utilisée."),
	LAST_ADMIN_REQUIRED(HttpStatus.CONFLICT, "Au moins un administrateur actif doit être conservé."),
	PASSWORD_POLICY_VIOLATION(HttpStatus.BAD_REQUEST, "Le mot de passe ne respecte pas la politique de sécurité."),

	// Rôles et permissions
	ROLE_NOT_FOUND(HttpStatus.NOT_FOUND, "Rôle introuvable."),
	ROLE_CODE_ALREADY_USED(HttpStatus.CONFLICT, "Ce code de rôle est déjà utilisé."),
	ROLE_IN_USE(HttpStatus.CONFLICT, "Ce rôle est attribué à des utilisateurs : retirez-le d'abord."),
	ROLE_NOT_EDITABLE(HttpStatus.CONFLICT, "Le rôle ADMIN détient toutes les permissions et ne peut pas être modifié."),
	ROLE_NOT_DELETABLE(HttpStatus.CONFLICT, "Les rôles système ne peuvent pas être supprimés."),
	UNKNOWN_PERMISSION(HttpStatus.BAD_REQUEST, "Permission inconnue."),
	PRIVILEGE_ESCALATION(HttpStatus.FORBIDDEN,
			"Vous ne pouvez pas accorder des permissions que vous ne possédez pas vous-même."),

	// Catalogue
	CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Catégorie introuvable."),
	CATEGORY_NAME_ALREADY_USED(HttpStatus.CONFLICT, "Une catégorie porte déjà ce nom."),
	CATEGORY_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Cette catégorie est désactivée : choisissez une catégorie active."),
	BRAND_NOT_FOUND(HttpStatus.NOT_FOUND, "Marque introuvable."),
	BRAND_NAME_ALREADY_USED(HttpStatus.CONFLICT, "Une marque porte déjà ce nom."),
	BRAND_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Cette marque est désactivée : choisissez une marque active."),
	PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "Produit introuvable."),
	SKU_ALREADY_USED(HttpStatus.CONFLICT, "Cette référence (SKU) est déjà utilisée."),
	BARCODE_ALREADY_USED(HttpStatus.CONFLICT, "Ce code-barres est déjà attribué à un autre produit."),
	PRODUCT_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Ce produit est désactivé."),

	// Fournisseurs
	SUPPLIER_NOT_FOUND(HttpStatus.NOT_FOUND, "Fournisseur introuvable."),
	SUPPLIER_NAME_ALREADY_USED(HttpStatus.CONFLICT, "Un fournisseur porte déjà ce nom."),
	SUPPLIER_CODE_ALREADY_USED(HttpStatus.CONFLICT, "Ce code fournisseur est déjà utilisé."),
	SUPPLIER_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Ce fournisseur est désactivé : choisissez un fournisseur actif."),

	// Clients
	CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND, "Client introuvable."),
	CUSTOMER_CODE_ALREADY_USED(HttpStatus.CONFLICT, "Ce code client est déjà utilisé."),
	CUSTOMER_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Ce client est désactivé."),
	CUSTOMER_CREATE_FORBIDDEN(HttpStatus.FORBIDDEN,
			"Vous n'avez pas le droit d'ajouter un client : choisissez un client existant ou vendez au comptoir."),
	CREDIT_LIMIT_CHANGE_FORBIDDEN(HttpStatus.FORBIDDEN,
			"Vous n'êtes pas autorisé à accorder ou modifier un plafond de crédit."),
	CREDIT_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Plafond de crédit du client dépassé."),
	AMOUNT_EXCEEDS_CUSTOMER_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT,
			"Montant supérieur au solde dû par le client."),
	CUSTOMER_ACCOUNT_ENTRY_ALREADY_RECORDED(HttpStatus.CONFLICT, "Cette pièce est déjà imputée au compte du client."),

	// Ventes
	SALE_NOT_FOUND(HttpStatus.NOT_FOUND, "Vente introuvable."),
	SALE_DUPLICATE_PRODUCT(HttpStatus.BAD_REQUEST,
			"Un même produit figure sur plusieurs lignes : regroupez les quantités sur une seule ligne."),
	PRODUCT_PRICE_NOT_SET(HttpStatus.UNPROCESSABLE_CONTENT, "Ce produit n'a pas de prix de vente pour ce tarif."),
	DISCOUNT_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT, "Remise supérieure au maximum autorisé."),
	PAID_AMOUNT_EXCEEDS_TOTAL(HttpStatus.BAD_REQUEST,
			"Le montant payé dépasse le total de la vente : la monnaie rendue ne s'enregistre pas."),
	PAYMENT_METHOD_REQUIRED(HttpStatus.BAD_REQUEST, "Mode de paiement requis pour un montant payé."),
	CUSTOMER_REQUIRED_FOR_CREDIT(HttpStatus.UNPROCESSABLE_CONTENT,
			"Une vente avec un reste à payer exige un client identifié."),
	SALE_AMOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "Montant de vente trop élevé."),
	INVALID_CASH_RECEIVED(HttpStatus.BAD_REQUEST,
			"Montant reçu invalide : en espèces seulement, et au moins égal au montant payé."),
	SALE_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Cette vente est déjà annulée."),
	SALE_CREDIT_ALREADY_SETTLED(HttpStatus.CONFLICT,
			"Le client a déjà réglé tout ou partie du crédit de cette vente : passez par un retour avec avoir."),

	// Caisse
	CASH_SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "Session de caisse introuvable."),
	CASH_SESSION_ALREADY_OPEN(HttpStatus.CONFLICT, "Vous avez déjà une caisse ouverte : clôturez-la d'abord."),
	NO_OPEN_CASH_SESSION(HttpStatus.CONFLICT, "Aucune caisse ouverte : ouvrez votre caisse avant d'encaisser."),
	CASH_SESSION_OTHER_WAREHOUSE(HttpStatus.CONFLICT,
			"Votre caisse est ouverte dans un autre dépôt : revenez dans ce dépôt pour l'utiliser ou la clôturer."),
	CASH_INSUFFICIENT(HttpStatus.UNPROCESSABLE_CONTENT, "Espèces insuffisantes dans la caisse pour cette sortie."),
	CASH_ENTRY_ALREADY_RECORDED(HttpStatus.CONFLICT, "Cette pièce est déjà enregistrée en caisse."),
	CASH_ENTRY_NOT_FOUND(HttpStatus.NOT_FOUND, "Opération de caisse introuvable."),
	CASH_ENTRY_NOT_REVERSIBLE(HttpStatus.CONFLICT,
			"Seuls les apports et sorties manuels se corrigent ici : une vente se corrige par annulation ou retour."),
	CASH_ENTRY_ALREADY_REVERSED(HttpStatus.CONFLICT, "Cette opération a déjà été corrigée."),

	// Retours clients
	RETURN_NOT_FOUND(HttpStatus.NOT_FOUND, "Retour introuvable."),
	AUDIT_LOG_NOT_FOUND(HttpStatus.NOT_FOUND, "Entrée du journal d'audit introuvable."),
	SALE_NOT_RETURNABLE(HttpStatus.CONFLICT, "Une vente annulée ne peut pas faire l'objet d'un retour."),
	SALE_HAS_RETURNS(HttpStatus.CONFLICT,
			"Cette vente a déjà fait l'objet d'un retour : elle ne peut plus être annulée, enregistrez un retour."),
	RETURN_PRODUCT_NOT_SOLD(HttpStatus.BAD_REQUEST, "Ce produit ne figure pas sur la vente."),
	RETURN_DUPLICATE_PRODUCT(HttpStatus.BAD_REQUEST, "Un même produit figure plusieurs fois dans le retour."),
	RETURN_QUANTITY_EXCEEDS_SOLD(HttpStatus.UNPROCESSABLE_CONTENT,
			"Quantité retournée supérieure à la quantité vendue restant à retourner."),
	RETURN_CUSTOMER_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT,
			"Un avoir sur compte exige une vente faite à un client identifié."),
	RETURN_REFUND_EXCEEDS_PAID(HttpStatus.UNPROCESSABLE_CONTENT,
			"Remboursement supérieur à ce que le client a payé sur cette vente : imputez le reste sur son compte."),

	// Inventaires
	INVENTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Inventaire introuvable."),
	INVENTORY_ALREADY_OPEN(HttpStatus.CONFLICT,
			"Un inventaire est déjà en cours dans ce dépôt : terminez-le ou annulez-le d'abord."),
	INVENTORY_INVALID_TRANSITION(HttpStatus.CONFLICT, "Action impossible à cette étape de l'inventaire."),
	INVENTORY_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT, "Aucun produit dans le périmètre de l'inventaire."),
	INVENTORY_LINES_NOT_COUNTED(HttpStatus.UNPROCESSABLE_CONTENT,
			"Tous les produits de l'inventaire doivent être comptés avant la validation."),
	INVENTORY_PRODUCT_NOT_IN_SCOPE(HttpStatus.BAD_REQUEST, "Ce produit ne fait pas partie de l'inventaire."),
	INVENTORY_DUPLICATE_PRODUCT(HttpStatus.BAD_REQUEST, "Un même produit figure plusieurs fois dans la demande."),

	// Achats
	PURCHASE_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Commande fournisseur introuvable."),
	PURCHASE_DUPLICATE_PRODUCT(HttpStatus.BAD_REQUEST,
			"Un même produit figure sur plusieurs lignes : regroupez les quantités sur une seule ligne."),
	PURCHASE_AMOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "Montant de commande trop élevé."),
	PURCHASE_ORDER_NOT_RECEIVABLE(HttpStatus.CONFLICT,
			"Cette commande ne peut plus être réceptionnée : elle est déjà reçue, soldée ou annulée."),
	PURCHASE_ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT,
			"Seule une commande sans aucune réception peut être annulée."),
	PURCHASE_ORDER_NOT_CLOSABLE(HttpStatus.CONFLICT,
			"Seule une commande partiellement reçue peut être soldée."),
	RECEIPT_PRODUCT_NOT_ORDERED(HttpStatus.BAD_REQUEST, "Ce produit ne figure pas sur la commande."),
	RECEIPT_QUANTITY_EXCEEDS_REMAINING(HttpStatus.UNPROCESSABLE_CONTENT,
			"Quantité reçue supérieure au reste à recevoir."),
	PURCHASE_ORDER_PREPAID(HttpStatus.CONFLICT,
			"Les paiements versés dépassent la valeur reçue : la commande ne peut être ni annulée ni soldée."),
	PURCHASE_ORDER_PAYMENT_EXCEEDS_REMAINING(HttpStatus.UNPROCESSABLE_CONTENT,
			"Montant supérieur au reste à payer de la commande."),
	SUPPLIER_PAYMENT_EXCEEDS_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT,
			"Montant supérieur au reste à payer à ce fournisseur."),
	SUPPLIER_PAYMENT_ORDER_MISMATCH(HttpStatus.BAD_REQUEST, "Cette commande n'est pas de ce fournisseur."),
	SUPPLIER_PAYMENT_DATE_IN_FUTURE(HttpStatus.BAD_REQUEST, "La date du paiement ne peut pas être dans le futur."),
	PURCHASE_ORDER_NOT_INVOICEABLE(HttpStatus.CONFLICT,
			"Facturation possible une fois la commande entièrement reçue ou soldée."),
	PURCHASE_ORDER_ALREADY_INVOICED(HttpStatus.CONFLICT, "Cette commande a déjà sa facture fournisseur."),
	SUPPLIER_INVOICE_NUMBER_ALREADY_USED(HttpStatus.CONFLICT, "Ce numéro de facture existe déjà pour ce fournisseur."),
	SUPPLIER_INVOICE_DATE_INVALID(HttpStatus.BAD_REQUEST,
			"La date de facture ne peut être ni dans le futur ni antérieure à la commande."),

	// Dépenses
	EXPENSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Dépense introuvable."),
	EXPENSE_DATE_IN_FUTURE(HttpStatus.BAD_REQUEST, "La date de la dépense ne peut pas être dans le futur."),
	EXPENSE_ALREADY_CANCELLED(HttpStatus.CONFLICT, "Cette dépense est déjà annulée."),

	// Rendez-vous
	APPOINTMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Rendez-vous introuvable."),
	APPOINTMENT_CONTACT_REQUIRED(HttpStatus.BAD_REQUEST,
			"Indiquez un client du fichier, ou le nom et le téléphone du prospect."),
	APPOINTMENT_IN_PAST(HttpStatus.BAD_REQUEST, "Un rendez-vous se planifie dans le futur."),
	APPOINTMENT_SLOT_TAKEN(HttpStatus.CONFLICT, "Un rendez-vous est déjà planifié à cette heure dans ce dépôt."),
	APPOINTMENT_CLOSED(HttpStatus.CONFLICT, "Ce rendez-vous est clos : il ne se modifie plus."),
	APPOINTMENT_NOT_YET_DUE(HttpStatus.CONFLICT,
			"Un rendez-vous ne peut être déclaré honoré ou manqué qu'à partir de son jour."),
	APPOINTMENT_CANCELLATION_REASON_REQUIRED(HttpStatus.BAD_REQUEST, "Indiquez le motif de l'annulation."),

	// Dépôts
	WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Dépôt introuvable."),
	WAREHOUSE_CODE_ALREADY_USED(HttpStatus.CONFLICT, "Ce code de dépôt est déjà utilisé."),
	WAREHOUSE_NAME_ALREADY_USED(HttpStatus.CONFLICT, "Un dépôt porte déjà ce nom."),
	WAREHOUSE_INACTIVE(HttpStatus.FORBIDDEN, "Ce dépôt est désactivé : choisissez un autre dépôt."),
	MAIN_WAREHOUSE_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT,
			"Un dépôt désactivé ne peut pas devenir le dépôt principal : réactivez-le d'abord."),
	MAIN_WAREHOUSE_REQUIRED(HttpStatus.CONFLICT,
			"Le dépôt principal ne peut pas être désactivé : désignez d'abord un autre dépôt principal."),
	WAREHOUSE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Vous n'avez pas accès à ce dépôt."),
	NO_WAREHOUSE_AVAILABLE(HttpStatus.FORBIDDEN, "Aucun dépôt actif ne vous est attribué."),
	INVALID_WAREHOUSE_ID(HttpStatus.BAD_REQUEST, "Identifiant de dépôt invalide."),
	WAREHOUSE_MISMATCH(HttpStatus.CONFLICT,
			"Cette opération concerne un autre dépôt : sélectionnez ce dépôt pour la réaliser."),

	// Stock
	STOCK_INSUFFICIENT(HttpStatus.UNPROCESSABLE_CONTENT, "Stock insuffisant."),
	INVALID_STOCK_QUANTITY(HttpStatus.BAD_REQUEST, "Quantité invalide."),
	STOCK_MOVEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Mouvement de stock introuvable."),
	MOVEMENT_NOT_REVERSIBLE(HttpStatus.CONFLICT,
			"Ce mouvement ne peut pas être annulé ici : annulez la pièce qui l'a produit (vente, réception, transfert…)."),
	MOVEMENT_ALREADY_REVERSED(HttpStatus.CONFLICT, "Ce mouvement a déjà été annulé."),
	SAME_WAREHOUSE_TRANSFER(HttpStatus.BAD_REQUEST, "Magasins identiques."),
	STOCK_TRANSFER_NOT_FOUND(HttpStatus.NOT_FOUND, "Transfert introuvable."),
	STOCK_TRANSFER_ALREADY_RETURNED(HttpStatus.CONFLICT, "Ce transfert a déjà été retourné au dépôt d'origine."),
	STOCK_TRANSFER_DUPLICATE_PRODUCT(HttpStatus.BAD_REQUEST,
			"Un même produit figure sur plusieurs lignes : regroupez les quantités sur une seule ligne."),

	// Devis
	QUOTE_NOT_FOUND(HttpStatus.NOT_FOUND, "Devis introuvable."),
	QUOTE_DUPLICATE_PRODUCT(HttpStatus.BAD_REQUEST,
			"Un même produit figure sur plusieurs lignes : regroupez les quantités sur une seule ligne."),
	QUOTE_NOT_EDITABLE(HttpStatus.CONFLICT, "Ce devis ne peut plus être modifié (accepté, refusé, facturé ou annulé)."),
	QUOTE_INVALID_TRANSITION(HttpStatus.CONFLICT, "Action impossible dans l'état actuel du devis."),
	QUOTE_ALREADY_INVOICED(HttpStatus.CONFLICT, "Ce devis a déjà été facturé."),
	QUOTE_SALE_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT,
			"Cette vente ne correspond pas au devis (client, dépôt ou date) : elle ne peut pas en être la facture."),
	SALE_ALREADY_LINKED_TO_QUOTE(HttpStatus.CONFLICT, "Cette vente est déjà la facture d'un autre devis."),
	QUOTE_AMOUNT_OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "Montant de devis trop élevé."),
	QUOTE_BENEFICIARIES_REQUIRED(HttpStatus.BAD_REQUEST,
			"Renseignez le nombre de bénéficiaires : une ligne est chiffrée par bénéficiaire."),

	// Paramètres des factures
	INVALID_LOGO(HttpStatus.BAD_REQUEST, "Logo invalide : seules les images PNG ou JPEG sont acceptées."),
	FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Fichier trop volumineux : 2 Mo maximum."),
	LOGO_NOT_FOUND(HttpStatus.NOT_FOUND, "Aucun logo enregistré."),

	// Référentiels utilisés par les utilisateurs
	UNKNOWN_ROLE(HttpStatus.BAD_REQUEST, "Rôle inconnu."),
	UNKNOWN_WAREHOUSE(HttpStatus.BAD_REQUEST, "Dépôt inconnu ou inactif."),

	// 5xx
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Une erreur interne est survenue."),
	SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service momentanément indisponible.");

	private final HttpStatus status;

	private final String defaultMessage;

	ErrorCode(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public HttpStatus status() {
		return this.status;
	}

	public String defaultMessage() {
		return this.defaultMessage;
	}

	/**
	 * Code générique correspondant à un statut HTTP, pour les erreurs qui ne viennent pas
	 * d'une {@link ApiException} (exceptions du framework).
	 */
	public static ErrorCode fromStatus(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> INVALID_PARAMETER;
			case 401 -> UNAUTHENTICATED;
			case 403 -> ACCESS_DENIED;
			case 404 -> RESOURCE_NOT_FOUND;
			case 405 -> METHOD_NOT_ALLOWED;
			case 406 -> NOT_ACCEPTABLE;
			case 409 -> CONFLICT;
			case 415 -> UNSUPPORTED_MEDIA_TYPE;
			case 422 -> BUSINESS_RULE_VIOLATION;
			case 503 -> SERVICE_UNAVAILABLE;
			default -> status.is4xxClientError() ? INVALID_PARAMETER : INTERNAL_ERROR;
		};
	}

}
