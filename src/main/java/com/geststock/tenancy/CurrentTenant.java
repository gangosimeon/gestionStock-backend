package com.geststock.tenancy;

import org.springframework.stereotype.Component;

/**
 * Entreprise du traitement en cours : contexte de la requête, sinon entreprise par défaut (dev/test).
 * Sert à émettre les jetons et à décrire l'entreprise courante ; jamais à choisir une base à partir
 * d'une donnée du client.
 */
@Component
public class CurrentTenant {

	private final DefaultTenant defaultTenant;

	private final TenantDirectory directory;

	CurrentTenant(DefaultTenant defaultTenant, TenantDirectory directory) {
		this.defaultTenant = defaultTenant;
		this.directory = directory;
	}

	public TenantRef require() {
		return TenantContext.current().or(this.defaultTenant::get).orElseThrow(TenantContextMissingException::new);
	}

	/** Entreprise courante avec son statut et son nom. */
	public TenantDirectory.TenantStatus status() {
		TenantRef ref = require();
		return this.directory.findById(ref.companyId()).orElseThrow(TenantContextMissingException::new);
	}

}
