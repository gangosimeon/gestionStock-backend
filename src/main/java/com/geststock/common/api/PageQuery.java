package com.geststock.common.api;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.geststock.exception.InvalidRequestException;

/**
 * Paramètres de pagination communs ({@code ?page=0&size=20&sort=champ,asc}).
 *
 * <p><b>Mode de compatibilité</b> : le front actuel charge certaines listes sans paramètre
 * et attend la collection complète. Sans {@code page} ni {@code size}, la requête renvoie
 * donc jusqu'à {@value #LEGACY_MAX_SIZE} éléments ; au-delà, la réponse est tronquée et
 * signalée par l'en-tête {@code X-Result-Truncated}. Ce mode disparaîtra quand le front
 * paginera côté serveur.
 */
@Schema(description = "Pagination : page (à partir de 0), size (1 à 200), sort=champ,asc|desc (répétable)")
public record PageQuery(Integer page, Integer size, List<String> sort) {

	public static final int DEFAULT_SIZE = 20;

	public static final int MAX_SIZE = 200;

	public static final int LEGACY_MAX_SIZE = 5_000;

	public static final String TRUNCATED_HEADER = "X-Result-Truncated";

	/** {@code true} si le client n'a demandé aucune pagination (mode de compatibilité). */
	public boolean isUnpaged() {
		return this.page == null && this.size == null;
	}

	public Pageable toPageable(SortWhitelist whitelist) {
		if (isUnpaged()) {
			return PageRequest.of(0, LEGACY_MAX_SIZE, whitelist.resolve(this.sort));
		}
		int pageNumber = this.page != null ? this.page : 0;
		int pageSize = this.size != null ? this.size : DEFAULT_SIZE;
		if (pageNumber < 0) {
			throw new InvalidRequestException("Paramètre invalide : page doit être positif ou nul.");
		}
		if (pageSize < 1 || pageSize > MAX_SIZE) {
			throw new InvalidRequestException("Paramètre invalide : size doit être compris entre 1 et " + MAX_SIZE + ".");
		}
		return PageRequest.of(pageNumber, pageSize, whitelist.resolve(this.sort));
	}

}
