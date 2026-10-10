package com.geststock.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.ActorIdentity;
import com.geststock.common.web.TraceIdFilter;
import com.geststock.security.CurrentUser;

/**
 * Journal d'audit métier : qui a fait quoi, quand, d'où, avec quel résultat.
 *
 * <p>L'écriture se fait <b>dans la transaction de l'opération tracée</b> (propagation
 * {@code MANDATORY}) : une opération annulée ne laisse pas de trace « réussie », et une
 * opération validée ne peut pas perdre sa trace. Utilisateur, rôles, adresse IP, navigateur
 * et identifiant de trace sont relevés par le serveur, jamais fournis par le client.
 */
@Service
public class AuditService {

	private final AuditLogRepository repository;

	private final JsonMapper json;

	private final Clock clock;

	AuditService(AuditLogRepository repository, JsonMapper json, Clock clock) {
		this.repository = repository;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Trace une opération réussie.
	 *
	 * @param action      verbe ({@code CREATE}, {@code CANCEL}…)
	 * @param entityType  type d'objet ({@code SALE}…)
	 * @param entityId    identifiant de l'objet
	 * @param entityLabel libellé lisible (numéro de pièce…)
	 * @param details     résumé en français
	 * @param afterState  état après l'opération, sérialisé en JSON (ou {@code null})
	 * @param warehouseId dépôt concerné (ou {@code null})
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void success(String action, String entityType, String entityId, String entityLabel, String details,
			Object afterState, UUID warehouseId) {
		AuditLog.Builder entry = AuditLog.builder(Instant.now(this.clock), action, entityType, AuditStatus.SUCCESS)
			.entity(entityId, truncate(entityLabel, 255))
			.details(details)
			.states(null, afterState != null ? this.json.writeValueAsString(afterState) : null)
			.warehouseId(warehouseId)
			.traceId(MDC.get(TraceIdFilter.MDC_KEY));

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
			entry.user(user.id(), truncate(user.username(), 50),
					truncate(user.roles().stream().sorted().collect(Collectors.joining(",")), 50));
		}
		else {
			entry.user(ActorIdentity.currentUserId().orElse(null), null, null);
		}

		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			HttpServletRequest request = attributes.getRequest();
			entry.client(truncate(request.getRemoteAddr(), 45), truncate(request.getHeader("User-Agent"), 255));
		}
		// Écrite immédiatement : une trace invalide fait échouer l'opération sur-le-champ.
		this.repository.saveAndFlush(entry.build());
	}

	private static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

}
