package com.geststock.integration;

import java.util.OptionalLong;

/**
 * Acquittements du flux par les liaisons ({@code POST /catalog/changes/ack}, D5) : la purge ne supprime jamais un
 * événement qu'une liaison active n'a pas acquitté. Lot 1 : aucune liaison, donc aucun acquittement
 * ({@link NoAcknowledgements}) ; la vraie source viendra au lot 2.
 */
@FunctionalInterface
public interface AckSource {

	/** Plus petit {@code seq} acquitté par les liaisons actives ; vide si aucune liaison active n'a acquitté. */
	OptionalLong minimumAcknowledged();

}
