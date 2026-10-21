package com.geststock.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Annuaire des entreprises vu par le routage (implémenté par la plateforme). */
public interface TenantDirectory {

	/** Entreprise par code public, quel que soit son statut. */
	Optional<TenantStatus> findByCode(String code);

	/** Entreprise par identifiant, quel que soit son statut. */
	Optional<TenantStatus> findById(UUID companyId);

	/** Paramètres de connexion à la base de l'entreprise. */
	TenantConnection connection(UUID companyId);

	/** Entreprises actives (migrations au démarrage). */
	List<TenantRef> activeTenants();

	/**
	 * Entreprise et statut.
	 *
	 * @param ref    identifiant et code
	 * @param status statut plateforme ({@code ACTIVE}, {@code SUSPENDED}, …)
	 * @param name   nom commercial (affichage)
	 */
	record TenantStatus(TenantRef ref, String status, String name) {

		public boolean isActive() {
			return "ACTIVE".equals(this.status);
		}

		public boolean isSuspended() {
			return "SUSPENDED".equals(this.status);
		}

	}

}
