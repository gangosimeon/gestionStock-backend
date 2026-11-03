package com.geststock.integration.maintenance;

import java.util.List;

import org.springframework.stereotype.Component;

import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;

/**
 * Entreprises visitées par la maintenance : les entreprises actives qui ont une base, dans l'ordre de leur numéro
 * ({@link TenantDirectory#activeTenants()}) ; l'ordre de passage est ensuite fixé par {@link MaintenanceCompanies}.
 */
@Component
class MaintenanceTenants {

	private final TenantDirectory directory;

	MaintenanceTenants(TenantDirectory directory) {
		this.directory = directory;
	}

	List<TenantRef> active() {
		return this.directory.activeTenants();
	}

}
