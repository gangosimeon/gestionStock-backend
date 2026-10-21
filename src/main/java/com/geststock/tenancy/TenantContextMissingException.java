package com.geststock.tenancy;

/**
 * Accès à une base d'entreprise sans entreprise courante : erreur de programmation (traitement hors
 * requête sans {@link TenantContext#callAs}), jamais résolue par une base « par défaut » en production.
 */
public class TenantContextMissingException extends IllegalStateException {

	public TenantContextMissingException() {
		super("Aucune entreprise courante : accès à une base d'entreprise refusé.");
	}

}
