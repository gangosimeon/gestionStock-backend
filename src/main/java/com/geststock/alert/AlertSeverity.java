package com.geststock.alert;

/** Gravité, de la plus forte à la plus faible (ordre de tri des alertes). */
public enum AlertSeverity {

	/** Bloquant : on ne peut plus vendre. */
	CRITICAL,

	/** À traiter rapidement. */
	WARNING,

	/** À surveiller. */
	INFO

}
