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

	// Dépôts
	WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Dépôt introuvable."),
	WAREHOUSE_CODE_ALREADY_USED(HttpStatus.CONFLICT, "Ce code de dépôt est déjà utilisé."),
	WAREHOUSE_NAME_ALREADY_USED(HttpStatus.CONFLICT, "Un dépôt porte déjà ce nom."),
	WAREHOUSE_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Ce dépôt est désactivé."),
	MAIN_WAREHOUSE_REQUIRED(HttpStatus.CONFLICT,
			"Le dépôt principal ne peut pas être désactivé : désignez d'abord un autre dépôt principal."),
	WAREHOUSE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Vous n'avez pas accès à ce dépôt."),
	NO_WAREHOUSE_AVAILABLE(HttpStatus.FORBIDDEN, "Aucun dépôt actif ne vous est attribué."),

	// Stock
	STOCK_INSUFFICIENT(HttpStatus.UNPROCESSABLE_CONTENT, "Stock insuffisant."),
	INVALID_STOCK_QUANTITY(HttpStatus.BAD_REQUEST, "Quantité invalide."),
	STOCK_MOVEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Mouvement de stock introuvable."),
	MOVEMENT_NOT_REVERSIBLE(HttpStatus.CONFLICT,
			"Ce mouvement ne peut pas être annulé ici : annulez la pièce qui l'a produit (vente, réception, transfert…)."),
	MOVEMENT_ALREADY_REVERSED(HttpStatus.CONFLICT, "Ce mouvement a déjà été annulé."),
	SAME_WAREHOUSE_TRANSFER(HttpStatus.BAD_REQUEST, "Magasins identiques."),

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
