package com.geststock.integration;

import java.util.List;

/**
 * Page du flux de changements (schéma {@code ChangePage} du contrat {@code integration/v1}).
 *
 * @param items   événements servis, par {@code seq} croissant, après filtre de la liaison et fusion des
 *                événements minces
 * @param hasMore P2 : il existe un événement de {@code seq > lastSeq} ou un événement validé encore sans
 *                {@code seq} ; rappeler immédiatement avec {@code after = lastSeq}
 * @param lastSeq P1 : dernier {@code seq} examiné, événements filtrés compris ; {@code after} si rien n'a été
 *                examiné ; toujours enregistré par le consommateur comme nouvel {@code after}
 */
public record ChangePage(List<ChangeEvent> items, boolean hasMore, long lastSeq) {

	public ChangePage {
		items = List.copyOf(items);
	}

}
