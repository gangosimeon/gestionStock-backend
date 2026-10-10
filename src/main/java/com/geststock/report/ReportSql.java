package com.geststock.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.InvalidRequestException;

/**
 * Exécution paginée des rapports : la base compte, trie et découpe ({@code LIMIT}/{@code OFFSET}) ;
 * seule la page demandée est chargée en mémoire. Le tri n'accepte que les colonnes déclarées
 * (liste blanche → alias SQL), jamais un texte fourni par le client.
 */
@Component
class ReportSql {

	private final NamedParameterJdbcTemplate jdbc;

	ReportSql(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	NamedParameterJdbcTemplate jdbc() {
		return this.jdbc;
	}

	/**
	 * @param sql       requête complète sans {@code ORDER BY} ni {@code LIMIT}
	 * @param sorts tris autorisés : nom d'API → alias de colonne de {@code sql} ; ses clés de
	 *              départage (identifiant de la ligne) garantissent un ordre stable entre pages
	 */
	<T> Page<T> page(String sql, MapSqlParameterSource params, PageQuery query, SortWhitelist sorts,
			RowMapper<T> mapper) {
		// Rapports toujours paginés : sans page ni size, première page de taille par défaut.
		PageQuery paged = new PageQuery(query.page() != null ? query.page() : 0, query.size(), query.sort());
		Pageable pageable = paged.toPageable(sorts);
		Long total = this.jdbc.queryForObject("select count(*) from (" + sql + ") q", params, Long.class);
		if (total == null || total == 0 || pageable.getOffset() >= total) {
			return new PageImpl<>(List.of(), pageable, total != null ? total : 0);
		}
		List<String> orders = new ArrayList<>();
		for (Sort.Order order : pageable.getSort()) {
			orders.add(order.getProperty() + (order.isAscending() ? " asc" : " desc") + " nulls last");
		}
		MapSqlParameterSource pageParams = new MapSqlParameterSource(params.getValues())
			.addValue("pageLimit", pageable.getPageSize())
			.addValue("pageOffset", pageable.getOffset());
		List<T> rows = this.jdbc.query("select * from (" + sql + ") q order by " + String.join(", ", orders)
				+ " limit :pageLimit offset :pageOffset", pageParams, mapper);
		return new PageImpl<>(rows, pageable, total);
	}

	/** Valeur de statut parmi celles autorisées (majuscules), ou {@code null}. */
	static String status(String value, Set<String> allowed) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.trim().toUpperCase(Locale.ROOT);
		if (!allowed.contains(normalized)) {
			throw new InvalidRequestException(
					"Paramètre invalide : " + value + " (valeurs admises : " + String.join(", ", allowed) + ").");
		}
		return normalized;
	}

	/** Conditions {@code WHERE} et paramètres, ajoutés seulement quand la valeur est fournie. */
	static final class Criteria {

		private final List<String> conditions = new ArrayList<>();

		private final MapSqlParameterSource params = new MapSqlParameterSource();

		Criteria param(String name, Object value) {
			this.params.addValue(name, value);
			return this;
		}

		/** Condition toujours appliquée. */
		Criteria and(String condition) {
			this.conditions.add(condition);
			return this;
		}

		/** Condition appliquée si {@code value} n'est pas nulle ; {@code :name} y désigne la valeur. */
		Criteria and(String condition, String name, Object value) {
			if (value != null) {
				this.conditions.add(condition);
				this.params.addValue(name, value);
			}
			return this;
		}

		/** Dépôt du rapport ({@code null} : tous). */
		Criteria warehouse(String column, UUID warehouseId) {
			return and(column + " = :warehouseId", "warehouseId", warehouseId);
		}

		Criteria period(String column, ReportPeriod period) {
			this.params.addValue("periodStart", period.start()).addValue("periodEnd", period.end());
			return and(column + " >= :periodStart and " + column + " < :periodEnd");
		}

		String where() {
			return this.conditions.isEmpty() ? "" : " where " + String.join(" and ", this.conditions);
		}

		MapSqlParameterSource params() {
			return this.params;
		}

	}

}
