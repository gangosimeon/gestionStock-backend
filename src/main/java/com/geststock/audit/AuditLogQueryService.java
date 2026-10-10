package com.geststock.audit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.geststock.audit.dto.AuditLogFilter;
import com.geststock.audit.dto.AuditLogResponse;
import com.geststock.audit.dto.AuditLogStatsResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;

/** Consultation du journal d'audit : recherche, détail, statistiques, export CSV. */
@Service
@Transactional(readOnly = true)
class AuditLogQueryService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of("createdAt", "createdAt", "action", "action",
			"module", "module", "username", "username", "status", "status"), Sort.by(Sort.Direction.DESC, "createdAt"));

	/** Plafond d'un export CSV (au-delà : affiner les filtres). */
	static final int EXPORT_MAX_ROWS = 50_000;

	static final String CSV_HEADER = "Date;Utilisateur;Rôle;Action;Entité;ID Entité;Label;IP;Statut;Détails;Module";

	private final AuditLogRepository repository;

	private final AuditService audit;

	private final JsonMapper json;

	private final Clock clock;

	AuditLogQueryService(AuditLogRepository repository, AuditService audit, JsonMapper json, Clock clock) {
		this.repository = repository;
		this.audit = audit;
		this.json = json;
		this.clock = clock;
	}

	Page<AuditLogResponse> search(AuditLogFilter filter, PageQuery query) {
		return this.repository.findAll(specification(filter), query.toPageable(SORTS)).map(this::toResponse);
	}

	AuditLogResponse get(UUID id) {
		return this.repository.findById(id)
			.map(this::toResponse)
			.orElseThrow(() -> new NotFoundException(ErrorCode.AUDIT_LOG_NOT_FOUND,
					ErrorCode.AUDIT_LOG_NOT_FOUND.defaultMessage()));
	}

	AuditLogStatsResponse stats() {
		Instant now = Instant.now(this.clock);
		Instant startOfDay = LocalDate.now(this.clock).atStartOfDay(this.clock.getZone()).toInstant();
		Instant last30Days = now.minus(Duration.ofDays(30));
		PageRequest first = PageRequest.of(0, 1);
		String mostActiveUser = this.repository.mostActiveUsers(last30Days, first).stream()
			.map(row -> (String) row[0])
			.findFirst()
			.orElse("-");
		String mostFrequentAction = this.repository.mostFrequentActions(last30Days, first).stream()
			.map(row -> (String) row[0])
			.findFirst()
			.orElse(null);
		return new AuditLogStatsResponse(this.repository.countByCreatedAtGreaterThanEqual(startOfDay),
				this.repository.countByStatusAndCreatedAtGreaterThanEqual(AuditStatus.FAILURE,
						now.minus(Duration.ofDays(7))),
				mostActiveUser, mostFrequentAction);
	}

	/**
	 * Export CSV (séparateur {@code ;}, UTF-8 avec BOM pour Excel), du plus récent au plus
	 * ancien. L'export lui-même est tracé ({@code EXPORT}).
	 */
	@Transactional
	Export export(AuditLogFilter filter) {
		Page<AuditLog> page = this.repository.findAll(specification(filter),
				PageRequest.of(0, EXPORT_MAX_ROWS, SORTS.defaultSort()));
		StringBuilder csv = new StringBuilder("﻿").append(CSV_HEADER).append("\r\n");
		for (AuditLog entry : page.getContent()) {
			csv.append(String.join(";", cell(entry.getCreatedAt().toString()), cell(entry.getUsername()),
					cell(entry.getUserRole()), cell(entry.getAction()), cell(entry.getEntityType()),
					cell(entry.getEntityId()), cell(entry.getEntityLabel()), cell(entry.getIpAddress()),
					cell(entry.getStatus().name()), cell(entry.getDetails()), cell(entry.getModule())))
				.append("\r\n");
		}
		boolean truncated = page.getTotalElements() > page.getContent().size();
		this.audit.record(AuditEvent.of("EXPORT", "AUDIT_LOG", null, "Journal d'audit")
			.details("Export CSV de " + page.getContent().size() + " entrée(s)"
					+ (truncated ? " (tronqué, " + page.getTotalElements() + " au total)" : ""))
			.meta("filter", filter)
			.meta("rows", page.getContent().size()));
		return new Export(csv.toString(), truncated);
	}

	record Export(String csv, boolean truncated) {
	}

	// --- Critères -------------------------------------------------------------------------------------

	private Specification<AuditLog> specification(AuditLogFilter filter) {
		Instant from = parseBound(filter.dateFrom(), false);
		Instant to = parseBound(filter.dateTo(), true);
		List<String> actions = upper(filter.actions());
		List<String> entityTypes = upper(filter.entityTypes());
		List<String> modules = upper(filter.modules());
		AuditStatus status = parseStatus(filter.status());
		String search = filter.search() != null && !filter.search().isBlank()
				? "%" + escape(filter.search().trim().toLowerCase(Locale.ROOT)) + "%" : null;
		return (root, query, cb) -> {
			List<Predicate> predicates = new java.util.ArrayList<>();
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
			}
			if (to != null) {
				predicates.add(cb.lessThan(root.get("createdAt"), to));
			}
			if (!actions.isEmpty()) {
				predicates.add(root.get("action").in(actions));
			}
			if (!entityTypes.isEmpty()) {
				predicates.add(root.get("entityType").in(entityTypes));
			}
			if (!modules.isEmpty()) {
				predicates.add(root.get("module").in(modules));
			}
			if (filter.userId() != null) {
				predicates.add(cb.equal(root.get("userId"), filter.userId()));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (filter.entityId() != null && !filter.entityId().isBlank()) {
				predicates.add(cb.equal(root.get("entityId"), filter.entityId().trim()));
			}
			if (search != null) {
				predicates.add(cb.or(cb.like(cb.lower(root.get("username")), search, '\\'),
						cb.like(cb.lower(root.get("entityLabel")), search, '\\'),
						cb.like(cb.lower(root.get("entityId")), search, '\\'),
						cb.like(cb.lower(root.get("details")), search, '\\')));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	/** Date seule : début de journée (borne basse) ou lendemain (borne haute exclue). */
	private Instant parseBound(String value, boolean upper) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String text = value.trim();
		try {
			if (text.length() == 10) {
				LocalDate date = LocalDate.parse(text);
				ZoneId zone = this.clock.getZone();
				return (upper ? date.plusDays(1) : date).atStartOfDay(zone).toInstant();
			}
			Instant instant = Instant.parse(text);
			return upper ? instant.plusMillis(1) : instant;
		}
		catch (DateTimeParseException ex) {
			throw new InvalidRequestException(
					"Paramètre invalide : date attendue au format AAAA-MM-JJ ou ISO-8601 (" + text + ").");
		}
	}

	private static AuditStatus parseStatus(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return AuditStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException("Paramètre invalide : status vaut SUCCESS ou FAILURE.");
		}
	}

	private static List<String> upper(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream()
			.filter(v -> v != null && !v.isBlank())
			.map(v -> v.trim().toUpperCase(Locale.ROOT))
			.toList();
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	/** Cellule CSV : guillemets si nécessaire ; neutralise les formules (=, +, -, @) pour Excel. */
	static String cell(String value) {
		if (value == null) {
			return "";
		}
		String text = value.replace("\r", " ").replace("\n", " ");
		if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
			text = "'" + text;
		}
		if (text.contains(";") || text.contains("\"")) {
			text = "\"" + text.replace("\"", "\"\"") + "\"";
		}
		return text;
	}

	// --- Conversion -----------------------------------------------------------------------------------

	private AuditLogResponse toResponse(AuditLog entry) {
		return new AuditLogResponse(entry.getId(), entry.getCreatedAt(), entry.getUserId(), entry.getUsername(),
				entry.getUserRole(), entry.getAction(), entry.getModule(), entry.getEntityType(), entry.getEntityId(),
				entry.getEntityLabel(), entry.getIpAddress(), entry.getUserAgent(), entry.getStatus().name(),
				tree(entry.getChanges()), entry.getDetails(), tree(entry.getBeforeState()), tree(entry.getAfterState()),
				tree(entry.getMeta()), entry.getWarehouseId(), entry.getTraceId());
	}

	private JsonNode tree(String value) {
		return value != null ? this.json.readTree(value) : null;
	}

}
