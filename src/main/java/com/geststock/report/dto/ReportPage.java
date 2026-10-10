package com.geststock.report.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.data.domain.Page;

/**
 * Page d'un rapport : lignes de la page et totaux calculés sur <b>toutes</b> les lignes du
 * filtre (par la base, sans les charger).
 *
 * @param summary totaux du rapport pour l'ensemble du filtre
 */
@Schema(name = "ReportPage")
public record ReportPage<T, S>(List<T> items, long total, int page, int size, int totalPages, S summary) {

	public static <T, S> ReportPage<T, S> of(Page<T> page, S summary) {
		return new ReportPage<>(page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize(),
				page.getTotalPages(), summary);
	}

}
