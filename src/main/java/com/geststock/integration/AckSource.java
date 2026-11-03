package com.geststock.integration;

/**
 * Acquittements du flux par les liaisons ({@code POST /catalog/changes/ack}, D5) : la purge ne supprime jamais un
 * événement qu'une liaison active ou suspendue n'a pas acquitté (plan § 30.4, v17-D4). Implémentation : liaisons
 * actives ou suspendues de l'<b>entreprise courante</b> lues dans la base plateforme, puis leurs acquittements dans la
 * base de l'entreprise ({@code integration_link_ack}). La purge lit son plafond {@code last_seq} <b>avant</b> d'appeler
 * cette source (v17-D11).
 */
@FunctionalInterface
public interface AckSource {

	/** Limite des acquittements pour l'entreprise courante. */
	AckFloor floor();

}
