package com.geststock.tenancy;

/** Plafond de pools atteint et aucun pool inactif à fermer : service momentanément indisponible. */
public class TenantCapacityException extends IllegalStateException {

	public TenantCapacityException() {
		super("Capacité de connexions atteinte : réessayer dans quelques instants.");
	}

}
