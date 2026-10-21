package com.geststock.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Entreprise visée par le traitement en cours.
 *
 * @param companyId identifiant interne (clé du pool de connexions)
 * @param code      code public, pour les journaux et messages
 */
public record TenantRef(UUID companyId, String code) {

	public TenantRef {
		Objects.requireNonNull(companyId, "companyId");
		Objects.requireNonNull(code, "code");
	}

}
