package com.geststock.common.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;

/**
 * Tris autorisés pour une ressource : nom exposé dans l'API → chemin JPA. Un critère hors
 * liste est refusé (400 {@code UNKNOWN_SORT_FIELD}) : pas d'exposition de la structure interne
 * ni de tri coûteux non indexé. La clé (par défaut {@code id}) est toujours ajoutée en
 * derniers critères pour garantir une pagination stable.
 *
 * @param fields      nom API → propriété JPA
 * @param defaultSort tri appliqué quand le client n'en demande pas
 * @param tieBreakers propriétés de la clé, ajoutées en fin de tri (clé composite possible)
 */
public record SortWhitelist(Map<String, String> fields, Sort defaultSort, List<String> tieBreakers) {

	public SortWhitelist {
		fields = Map.copyOf(fields);
		tieBreakers = List.copyOf(tieBreakers);
	}

	/** Ressource identifiée par {@code id}. */
	public SortWhitelist(Map<String, String> fields, Sort defaultSort) {
		this(fields, defaultSort, List.of("id"));
	}

	/**
	 * Convertit les paramètres {@code sort=champ,asc|desc} (répétables) en {@link Sort}.
	 *
	 * <p>Spring découpe une valeur contenant une virgule : {@code sort=username,desc} arrive
	 * sous la forme {@code ["username", "desc"]}. Un jeton {@code asc}/{@code desc} isolé
	 * s'applique donc au champ qui le précède.
	 */
	public Sort resolve(List<String> sortParams) {
		List<Sort.Order> orders = new ArrayList<>();
		if (sortParams != null) {
			for (String param : sortParams) {
				if (!StringUtils.hasText(param)) {
					continue;
				}
				for (String rawToken : param.split(",")) {
					String token = rawToken.trim();
					if (token.isEmpty()) {
						continue;
					}
					String lower = token.toLowerCase(Locale.ROOT);
					if (lower.equals("asc") || lower.equals("desc")) {
						if (orders.isEmpty()) {
							throw new InvalidRequestException(ErrorCode.UNKNOWN_SORT_FIELD,
									"Critère de tri incomplet : direction sans champ.");
						}
						Sort.Order last = orders.remove(orders.size() - 1);
						orders.add(last.with(lower.equals("desc") ? Sort.Direction.DESC : Sort.Direction.ASC));
						continue;
					}
					String property = this.fields.get(token);
					if (property == null) {
						throw new InvalidRequestException(ErrorCode.UNKNOWN_SORT_FIELD,
								"Critère de tri non autorisé : " + token + ".");
					}
					orders.add(Sort.Order.asc(property));
				}
			}
		}
		Sort sort = orders.isEmpty() ? this.defaultSort : Sort.by(orders);
		for (String key : this.tieBreakers) {
			if (sort.getOrderFor(key) == null) {
				sort = sort.and(Sort.by(Sort.Direction.ASC, key));
			}
		}
		return sort;
	}

}
