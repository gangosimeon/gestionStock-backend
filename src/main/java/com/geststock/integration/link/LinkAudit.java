package com.geststock.integration.link;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditModule;
import com.geststock.audit.AuditService;
import com.geststock.common.web.TraceIdFilter;
import com.geststock.platform.PlatformDb;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

import tools.jackson.databind.json.JsonMapper;

/**
 * Audit des liaisons boutique (lot 2, étape G12), en deux temps.
 * <ul>
 * <li><b>Trace de référence</b> ({@code platform.integration_audit_log}), écrite par {@link #record} dans la
 * transaction plateforme du changement de la liaison : une liaison ne change jamais d'état sans sa trace, et une
 * trace n'existe jamais sans son changement. Les tentatives refusées ({@link #recordAlone}) ont leur propre
 * transaction : elles survivent au refus.</li>
 * <li><b>Copie dans le journal de l'entreprise</b> ({@code audit_log} de sa base, {@link AuditService}, écran d'audit
 * de GestStock) des événements qui la concernent, après le commit de l'unité ({@link #unit}), au mieux : un échec est
 * journalisé en WARN, sans bloquer ; le rattrapage relève de la tâche planifiée du lot 3.</li>
 * </ul>
 * Jamais de secret, d'empreinte, de code, de {@code request_uri}, de jeton ni d'en-tête {@code Authorization}.
 */
@Component
class LinkAudit {

	private static final Logger log = LoggerFactory.getLogger(LinkAudit.class);

	static final String APPROVED = "LINK_REQUEST_APPROVED";

	static final String DENIED = "LINK_REQUEST_DENIED";

	static final String ACTIVATED = "LINK_ACTIVATED";

	static final String SUSPENDED = "LINK_SUSPENDED";

	static final String REACTIVATED = "LINK_REACTIVATED";

	static final String REVOKED = "LINK_REVOKED";

	static final String CODE_REPLAY_DETECTED = "CODE_REPLAY_DETECTED";

	static final String CODE_EXCHANGE_REJECTED = "CODE_EXCHANGE_REJECTED";

	static final String CLIENT_AUTHENTICATION_FAILED = "CLIENT_AUTHENTICATION_FAILED";

	/** Rotation du secret demandée par la boutique (G14). */
	static final String SECRET_ROTATED = "LINK_SECRET_ROTATED";

	/** Secret en attente devenu le secret courant (G14), avec sa cause. */
	static final String SECRET_PROMOTED = "LINK_SECRET_PROMOTED";

	/** Événements copiés dans le journal de l'entreprise concernée. */
	private static final Set<String> COMPANY_ACTIONS = Set.of(APPROVED, DENIED, SUSPENDED, REACTIVATED, REVOKED);

	private static final String INSERT = """
			insert into platform.integration_audit_log (action, severity, company_id, link_id, client_id, announced_client,
			  external_shop_id, actor_type, actor_id, actor_name, ip_address, reason, details, trace_id)
			values (:action, :severity, :company, :link, :client, :announced, :shop, :actorType, :actorId, :actorName, :ip,
			  :reason, cast(:details as jsonb), :traceId)""";

	/** Traces de l'unité en cours, à copier après son commit. */
	private static final ThreadLocal<List<Entry>> UNIT = new ThreadLocal<>();

	private final PlatformDb platform;

	private final AuditService companyAudit;

	private final JsonMapper json;

	private final TransactionTemplate companyTransaction;

	private final LinkPublication publication;

	LinkAudit(PlatformDb platform, AuditService companyAudit, JsonMapper json, PlatformTransactionManager transactionManager,
			LinkPublication publication) {
		this.platform = platform;
		this.publication = publication;
		this.companyAudit = companyAudit;
		this.json = json;
		this.companyTransaction = new TransactionTemplate(transactionManager);
		this.companyTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * Unité de travail : transaction plateforme ({@link PlatformDb#inTransaction}) ; après son commit, copie des traces
	 * qui concernent une entreprise. Imbriquée dans une autre unité, elle la rejoint (copie au commit de l'externe).
	 */
	<T> T unit(Supplier<T> work) {
		if (UNIT.get() != null) {
			return this.platform.inTransaction(status -> work.get());
		}
		List<Entry> entries = new ArrayList<>();
		UNIT.set(entries);
		T result;
		try {
			result = this.platform.inTransaction(status -> work.get());
		}
		finally {
			UNIT.remove();
		}
		entries.stream().filter(entry -> COMPANY_ACTIONS.contains(entry.action()) && entry.companyId() != null)
			.forEach(this::copyToCompany);
		// G13 : après une révocation, drapeau de publication resynchronisé (dernière liaison révoquée : arrêt).
		entries.stream().filter(entry -> REVOKED.equals(entry.action()) && entry.companyId() != null)
			.map(Entry::companyId).distinct().forEach(this::synchronizePublication);
		return result;
	}

	/** Au mieux : en cas d'échec, les événements continuent (sans danger), rattrapage par la tâche du lot 3. */
	private void synchronizePublication(UUID companyId) {
		try {
			this.publication.synchronize(companyId);
		}
		catch (RuntimeException ex) {
			log.warn("Drapeau de publication de l'entreprise {} non resynchronisé après une révocation ({}) : publication "
					+ "maintenue ; rattrapage par la tâche planifiée du lot 3.", companyId, ex.getClass().getSimpleName());
		}
	}

	/** Trace de référence dans la transaction plateforme courante (celle du changement). */
	void record(Entry entry) {
		insert(entry);
		List<Entry> unit = UNIT.get();
		if (unit != null) {
			unit.add(entry);
		}
	}

	/** Tentative refusée : trace de référence dans sa propre transaction plateforme (hors de toute unité). */
	void recordAlone(Entry entry) {
		this.platform.inTransaction(status -> {
			insert(entry);
			return null;
		});
	}

	private void insert(Entry entry) {
		this.platform.jdbc().sql(INSERT)
			.param("action", entry.action())
			.param("severity", entry.severity().name())
			.param("company", entry.companyId())
			.param("link", entry.linkId())
			.param("client", entry.clientId())
			.param("announced", entry.announcedClient())
			.param("shop", entry.shopId())
			.param("actorType", entry.actor().type().name())
			.param("actorId", entry.actor().id())
			.param("actorName", entry.actor().name())
			.param("ip", entry.ip())
			.param("reason", entry.reason() != null ? new MaskedText(entry.reason()) : null)
			.param("details", entry.details().isEmpty() ? null : this.json.writeValueAsString(entry.details()))
			.param("traceId", MDC.get(TraceIdFilter.MDC_KEY))
			.update();
	}

	/** Copie dans le journal de l'entreprise, après le commit ; un échec ne remonte jamais à l'appelant. */
	private void copyToCompany(Entry entry) {
		try {
			TenantRef company = new TenantRef(entry.companyId(), this.platform.jdbc()
				.sql("select code from platform.company where id = :id").param("id", entry.companyId()).query(String.class).single());
			TenantContext.runAs(company, () -> this.companyTransaction.executeWithoutResult(status -> {
				AuditEvent event = AuditEvent.of(entry.action(), "INTEGRATION_LINK", entry.linkId(), label(entry))
					.module(AuditModule.SECURITY)
					.details(sentence(entry));
				entry.details().forEach(event::meta);
				if (entry.reason() != null) {
					// Le motif libre reste dans la seule trace de référence : l'écriture JPA du journal de l'entreprise
					// journaliserait sa valeur en TRACE.
					event.meta("shopReasonProvided", true);
				}
				switch (entry.actor().type()) {
					case USER -> this.companyAudit.record(event);
					case SHOP -> this.companyAudit.machine(event, "liaison:" + entry.linkId());
					default -> this.companyAudit.machine(event, "système");
				}
			}));
		}
		catch (RuntimeException ex) {
			log.warn("Copie de l'audit {} de la liaison {} dans le journal de l'entreprise {} impossible ({}) : trace de "
					+ "référence conservée ; rattrapage par la tâche planifiée du lot 3.", entry.action(), entry.linkId(),
					entry.companyId(), ex.getClass().getSimpleName());
		}
	}

	private static String label(Entry entry) {
		String name = entry.shopName() != null ? entry.shopName() : "Boutique";
		return name + (entry.shopId() != null ? " (" + entry.shopId() + ")" : "");
	}

	private static String sentence(Entry entry) {
		String shop = label(entry);
		return switch (entry.action()) {
			case APPROVED -> "Liaison avec " + shop + " approuvée";
			case DENIED -> "Demande de liaison de " + shop + " refusée";
			case SUSPENDED -> "Liaison avec " + shop + " suspendue (" + entry.details().get("suspensionReason") + ")";
			case REACTIVATED -> "Liaison avec " + shop + " réactivée, dépôts reconfirmés";
			case REVOKED -> "Liaison avec " + shop + " révoquée (" + entry.details().get("revocationReason") + ")";
			default -> entry.action();
		};
	}

	// --- Traces -----------------------------------------------------------------------------------------------

	enum Severity {
		INFO, WARNING, CRITICAL
	}

	enum ActorType {
		USER, SHOP, SYSTEM, CLIENT
	}

	/**
	 * Auteur, jamais ambigu.
	 *
	 * @param id   USER : {@code app_user.id} ; SHOP : identifiant de la liaison ; CLIENT : {@code integration_client.id}
	 * @param name USER : nom d'utilisateur ; SHOP : boutique ; CLIENT : {@code client_id} public ; SYSTEM : mécanisme
	 */
	record Actor(ActorType type, UUID id, String name) {

		static Actor user(Authorizer person) {
			return new Actor(ActorType.USER, person.userId(), person.username());
		}

		static Actor shop(Link link) {
			return new Actor(ActorType.SHOP, link.id(), link.externalShopId());
		}

		static Actor system(String mechanism) {
			return new Actor(ActorType.SYSTEM, null, mechanism);
		}

		static Actor client(UUID clientId, String publicClientId) {
			return new Actor(ActorType.CLIENT, clientId, publicClientId);
		}

	}

	/**
	 * Une trace. {@code details} : motif codé, portées, dépôts, personne responsable, nature d'un refus ; jamais de
	 * secret.
	 */
	record Entry(String action, Severity severity, UUID companyId, UUID linkId, UUID clientId, String announcedClient,
			String shopId, String shopName, Actor actor, String ip, String reason, Map<String, Object> details) {

		/** Événement d'une liaison. */
		static Entry of(String action, Link link, Actor actor) {
			return new Entry(action, Severity.INFO, link.companyId(), link.id(), link.clientId(), null, link.externalShopId(),
					link.shopName(), actor, null, null, new LinkedHashMap<>());
		}

		Entry severity(Severity value) {
			return new Entry(this.action, value, this.companyId, this.linkId, this.clientId, this.announcedClient, this.shopId,
					this.shopName, this.actor, this.ip, this.reason, this.details);
		}

		Entry reason(String value) {
			return new Entry(this.action, this.severity, this.companyId, this.linkId, this.clientId, this.announcedClient,
					this.shopId, this.shopName, this.actor, this.ip, value, this.details);
		}

		Entry detail(String key, Object value) {
			this.details.put(key, value instanceof Set<?> set ? new TreeSet<>(set.stream().map(String::valueOf).toList()) : value);
			return this;
		}

	}

}
