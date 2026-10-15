package com.geststock.audit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springdoc.core.annotations.ParameterObject;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.audit.dto.AuditLogFilter;
import com.geststock.audit.dto.AuditLogResponse;
import com.geststock.audit.dto.AuditLogStatsResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;

/**
 * Consultation du journal d'audit (contrat front {@code /api/audit-logs}). Lecture seule :
 * aucune route n'écrit, ne modifie ni ne supprime d'entrée.
 */
@RestController
@RequestMapping("/api/audit-logs")
@Tag(name = "Journal d'audit")
@RequiresPermission(Permissions.AUDIT_READ)
class AuditLogController {

	private final AuditLogQueryService journal;

	AuditLogController(AuditLogQueryService journal) {
		this.journal = journal;
	}

	@GetMapping
	@Operation(summary = "Rechercher dans le journal",
			description = "Du plus récent au plus ancien. Filtres dateFrom, dateTo (AAAA-MM-JJ inclus ou instant ISO), "
					+ "actions, entityTypes, modules (A,B ou répétés), userId, status (SUCCESS | FAILURE), entityId, "
					+ "search, warehouseId. page (défaut 0) et size (défaut 20, max 200). Sans warehouse:all : seules les "
					+ "entrées de vos dépôts (pas celles sans dépôt) ; warehouseId hors de vos dépôts : 403.")
	ResponseEntity<PageResponse<AuditLogResponse>> list(@ParameterObject AuditLogFilter filter,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@Parameter(description = "createdAt | action | module | username | status, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		PageQuery query = new PageQuery(page != null ? page : 0, size, sort);
		return PageResponse.toResponse(this.journal.search(filter, query), query);
	}

	@GetMapping("/stats")
	@Operation(summary = "Statistiques du journal",
			description = "Entrées du jour, échecs des 7 derniers jours, utilisateur et action les plus fréquents sur "
					+ "30 jours, sur les entrées que vous pouvez consulter.")
	AuditLogStatsResponse stats() {
		return this.journal.stats();
	}

	@GetMapping(value = "/export", produces = "text/csv")
	@Operation(summary = "Exporter le journal (CSV)",
			description = "Mêmes filtres que la recherche ; 50 000 lignes au plus (en-tête X-Result-Truncated au-delà). "
					+ "Même périmètre que la recherche. L'export est lui-même tracé.")
	ResponseEntity<byte[]> export(@ParameterObject AuditLogFilter filter) {
		AuditLogQueryService.Export export = this.journal.export(filter);
		ResponseEntity.BodyBuilder response = ResponseEntity.ok()
			.contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
			.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"journal-audit.csv\"");
		if (export.truncated()) {
			response.header(PageQuery.TRUNCATED_HEADER, "true");
		}
		return response.body(export.csv().getBytes(StandardCharsets.UTF_8));
	}

	@GetMapping("/{id}")
	@Operation(summary = "Consulter une entrée du journal",
			description = "Entrée hors de vos dépôts (ou sans dépôt, sans warehouse:all) : 404.")
	AuditLogResponse get(@PathVariable UUID id) {
		return this.journal.get(id);
	}

}
