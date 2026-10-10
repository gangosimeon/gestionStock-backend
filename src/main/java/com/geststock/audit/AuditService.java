package com.geststock.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.ActorIdentity;
import com.geststock.common.web.TraceIdFilter;
import com.geststock.security.CurrentUser;

/**
 * Journal d'audit métier : qui a fait quoi, dans quel module, sur quel objet, quand, d'où,
 * avec quel résultat, et ce qui a changé.
 *
 * <p><b>Identité</b> : l'auteur (identifiant, nom d'utilisateur, rôles), l'adresse IP, le
 * navigateur et l'identifiant de trace sont relevés ici, côté serveur, dans le contexte de
 * sécurité et la requête. Aucune méthode n'accepte un auteur fourni par l'appelant, et
 * l'API n'offre aucune écriture du journal : le client ne peut pas falsifier une trace.
 *
 * <p><b>Transactions</b> :
 * <ul>
 * <li>réussite ({@link #record}) : écrite <b>dans la transaction de l'opération</b>
 * (propagation {@code MANDATORY}) ; une opération annulée ne laisse pas de trace « réussie »,
 * une opération validée ne peut pas perdre la sienne ;</li>
 * <li>échec ({@link #failure}, connexions) : écrit dans <b>sa propre transaction</b>
 * ({@code REQUIRES_NEW}), qui survit à l'annulation de l'opération refusée.</li>
 * </ul>
 */
@Service
public class AuditService {

	private final AuditLogRepository repository;

	private final AuditDiff diff;

	private final JsonMapper json;

	private final Clock clock;

	AuditService(AuditLogRepository repository, JsonMapper json, Clock clock) {
		this.repository = repository;
		this.diff = new AuditDiff(json);
		this.json = json;
		this.clock = clock;
	}

	/** Trace une opération réussie, dans la transaction de l'opération. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AuditEvent event) {
		save(build(event, AuditStatus.SUCCESS, currentActor()));
	}

	/**
	 * Trace une opération réussie (forme courte).
	 *
	 * @param afterState état après l'opération, sérialisé en JSON (ou {@code null})
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void success(String action, String entityType, String entityId, String entityLabel, String details,
			Object afterState, UUID warehouseId) {
		record(AuditEvent.of(action, entityType, entityId, entityLabel)
			.details(details)
			.after(afterState)
			.warehouse(warehouseId));
	}

	/**
	 * Trace une opération refusée ou en erreur, dans une transaction indépendante.
	 *
	 * @param errorCode  code d'erreur de l'API ({@code STOCK_INSUFFICIENT}…), ou {@code null}
	 * @param httpStatus statut HTTP renvoyé, ou 0
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failure(AuditEvent event, String errorCode, int httpStatus) {
		event.meta("errorCode", errorCode).meta("httpStatus", httpStatus > 0 ? httpStatus : null);
		save(build(event, AuditStatus.FAILURE, currentActor()));
	}

	/**
	 * Connexion ({@code LOGIN}) ou déconnexion ({@code LOGOUT}) réussie. L'identité est celle
	 * que le serveur vient de vérifier (mot de passe, jeton de rafraîchissement) : le contexte
	 * de sécurité n'est pas rempli sur ces routes publiques.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void authentication(String action, UUID userId, String username, Collection<String> roles) {
		if (!"LOGIN".equals(action) && !"LOGOUT".equals(action)) {
			throw new IllegalArgumentException("Action d'authentification inconnue : " + action);
		}
		AuditEvent event = AuditEvent.of(action, "USER", userId, username)
			.module(AuditModule.AUTH)
			.details(("LOGIN".equals(action) ? "Connexion de " : "Déconnexion de ") + username);
		save(build(event, AuditStatus.SUCCESS, new Actor(userId, username, joinRoles(roles))));
	}

	/**
	 * Connexion refusée. Le nom saisi n'est pas une identité vérifiée : il est noté comme
	 * libellé de l'objet visé, jamais comme auteur.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void loginFailed(String attemptedUsername, String errorCode) {
		AuditEvent event = AuditEvent.of("LOGIN", "USER", null, attemptedUsername != null ? attemptedUsername.trim() : null)
			.module(AuditModule.AUTH)
			.details("Connexion refusée (" + errorCode + ")")
			.meta("errorCode", errorCode)
			.meta("httpStatus", 401);
		save(build(event, AuditStatus.FAILURE, Actor.NONE));
	}

	// --- Construction -------------------------------------------------------------------------------

	private AuditLog build(AuditEvent event, AuditStatus status, Actor actor) {
		JsonNode before = this.diff.state(event.beforeState());
		JsonNode after = this.diff.state(event.afterState());
		JsonNode changes = this.diff.changes(before, after);
		AuditLog.Builder builder = AuditLog.builder(Instant.now(this.clock), event.action(), event.entityType(), status)
			.module(event.module().name())
			.user(actor.id(), truncate(actor.username(), 50), actor.roles())
			.entity(truncate(event.entityId(), 100), truncate(event.entityLabel(), 255))
			.details(event.details())
			.changes(changes != null && !changes.isEmpty() ? this.json.writeValueAsString(changes) : null)
			.states(before != null ? this.json.writeValueAsString(before) : null,
					after != null ? this.json.writeValueAsString(after) : null)
			.meta(event.meta().isEmpty() ? null : this.json.writeValueAsString(new LinkedHashMap<>(event.meta())))
			.warehouseId(event.warehouseId())
			.traceId(MDC.get(TraceIdFilter.MDC_KEY));
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			HttpServletRequest request = attributes.getRequest();
			// Adresse de la connexion (derrière un proxy : forward-headers-strategy du profil prod),
			// jamais un en-tête X-Forwarded-For brut que le client pourrait forger.
			builder.client(truncate(request.getRemoteAddr(), 45), truncate(request.getHeader("User-Agent"), 255));
		}
		return builder.build();
	}

	/** Auteur relevé dans le contexte de sécurité. */
	private static Actor currentActor() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
			return new Actor(user.id(), user.username(), joinRoles(user.roles()));
		}
		return new Actor(ActorIdentity.currentUserId().orElse(null), null, null);
	}

	private void save(AuditLog entry) {
		// Écrite immédiatement : une trace invalide fait échouer l'opération sur-le-champ.
		this.repository.saveAndFlush(entry);
	}

	private static String joinRoles(Collection<String> roles) {
		return roles == null || roles.isEmpty() ? null
				: truncate(roles.stream().sorted().collect(Collectors.joining(",")), 50);
	}

	static String truncate(String value, int max) {
		return value == null || value.length() <= max ? value : value.substring(0, max);
	}

	private record Actor(UUID id, String username, String roles) {

		static final Actor NONE = new Actor(null, null, null);

	}

}
