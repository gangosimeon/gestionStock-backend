package com.geststock.integration;

import java.util.List;
import java.util.UUID;

/**
 * Objets d'une section de l'instantané fournis par leur module ({@code categories}, {@code products}), avec le
 * <b>même mappeur</b> que leurs événements : une seule définition du format de chaque type. Lu dans la transaction
 * de page de l'instantané ; pagination par clé ({@code id} croissant), objets archivés compris.
 */
public interface SnapshotSource {

	/** Section servie : {@link SnapshotSection#CATEGORIES} ou {@link SnapshotSection#PRODUCTS}. */
	SnapshotSection section();

	/**
	 * @param afterId dernier identifiant servi dans la section (l'UUID nul au début)
	 * @param limit   nombre maximal d'objets
	 * @return au plus {@code limit} objets d'identifiant supérieur à {@code afterId}, par identifiant croissant
	 */
	List<SnapshotItem> after(UUID afterId, int limit);

}
