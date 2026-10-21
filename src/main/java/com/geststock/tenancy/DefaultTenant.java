package com.geststock.tenancy;

import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Entreprise visée sans contexte ({@code geststock.tenancy.default-company}), pour les outils de
 * développement et les tests uniquement : {@code ProductionSafetyCheck} refuse cette option en
 * production. Résolue paresseusement (l'entreprise historique peut être enregistrée après le démarrage
 * de ce composant).
 */
@Component
public class DefaultTenant {

	private final TenancyProperties properties;

	private final TenantDirectory directory;

	private volatile TenantRef resolved;

	DefaultTenant(TenancyProperties properties, TenantDirectory directory) {
		this.properties = properties;
		this.directory = directory;
	}

	public boolean isConfigured() {
		return this.properties.hasDefaultCompany();
	}

	public Optional<TenantRef> get() {
		if (!isConfigured()) {
			return Optional.empty();
		}
		TenantRef tenant = this.resolved;
		if (tenant == null) {
			tenant = this.directory.findByCode(this.properties.defaultCompany())
				.map(TenantDirectory.TenantStatus::ref)
				.orElseThrow(() -> new IllegalStateException(
						"Entreprise par défaut « " + this.properties.defaultCompany() + " » inconnue de la plateforme."));
			this.resolved = tenant;
		}
		return Optional.of(tenant);
	}

}
