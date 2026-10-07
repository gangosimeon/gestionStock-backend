package com.geststock.common.api;

import java.util.List;
import java.util.function.Function;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;

/**
 * Enveloppe standard des listes. Le front lit {@code items} et {@code total} (contrat
 * historique {@code PaginatedResult<T>}) ; {@code page}, {@code size} et {@code totalPages}
 * complètent la pagination serveur.
 *
 * <p>Ne jamais renvoyer directement un {@link Page} Spring Data : sa forme JSON n'est pas
 * un contrat stable.
 */
@Schema(name = "PageResponse", description = "Liste paginée")
public record PageResponse<T>(
		@Schema(description = "Éléments de la page") List<T> items,
		@Schema(description = "Nombre total d'éléments") long total,
		@Schema(description = "Numéro de page (à partir de 0)") int page,
		@Schema(description = "Taille de page") int size,
		@Schema(description = "Nombre total de pages") int totalPages) {

	public PageResponse {
		items = items != null ? List.copyOf(items) : List.of();
	}

	public static <T> PageResponse<T> of(Page<T> page) {
		return new PageResponse<>(page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize(),
				page.getTotalPages());
	}

	public static <E, T> PageResponse<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
		return of(page.map(mapper));
	}

	/**
	 * Réponse HTTP d'une liste paginée. En mode de compatibilité (aucune pagination demandée),
	 * signale par l'en-tête {@value PageQuery#TRUNCATED_HEADER} une liste tronquée.
	 */
	public static <T> ResponseEntity<PageResponse<T>> toResponse(Page<T> page, PageQuery query) {
		ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
		if (query.isUnpaged() && page.getTotalElements() > page.getNumberOfElements()) {
			builder.header(PageQuery.TRUNCATED_HEADER, "true");
		}
		return builder.body(of(page));
	}

	/** Liste complète non paginée (référentiels bornés, mode de compatibilité). */
	public static <T> PageResponse<T> unpaged(List<T> items) {
		int count = items != null ? items.size() : 0;
		return new PageResponse<>(items, count, 0, count, count == 0 ? 0 : 1);
	}

}
