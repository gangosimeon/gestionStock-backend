package com.geststock.appointment;

/** Statut d'un rendez-vous (contrainte {@code ck_appointment_status}). */
public enum AppointmentStatus {

	/** Planifié : seul statut modifiable. */
	SCHEDULED,

	/** Honoré : le client est venu. */
	COMPLETED,

	/** Absent : le client n'est pas venu. */
	NO_SHOW,

	/** Annulé, avec un motif. */
	CANCELLED;

	public boolean isFinal() {
		return this != SCHEDULED;
	}

}
