package com.geststock.platform.audit;

import java.util.UUID;

import org.slf4j.MDC;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.geststock.common.web.TraceIdFilter;
import com.geststock.platform.PlatformDb;
import com.geststock.platform.admin.PlatformAuthentication;

/**
 * Journal des opérations d'administration de la plateforme. Acteur : Super Admin de la requête (ou
 * système). {@code details} ne contient jamais de secret (mot de passe, jeton, clé).
 */
@Component
public class PlatformAudit {

	private final PlatformDb db;

	PlatformAudit(PlatformDb db) {
		this.db = db;
	}

	public void record(String action, UUID companyId, String details) {
		UUID actorId = null;
		String actorName = null;
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof PlatformAuthentication platform) {
			actorId = platform.getPrincipal().id();
			actorName = platform.getPrincipal().username();
		}
		record(action, companyId, details, actorId, actorName);
	}

	public void record(String action, UUID companyId, String details, UUID actorId, String actorName) {
		this.db.jdbc()
			.sql("""
					insert into platform.platform_audit_log (actor_id, actor_name, action, company_id, details, trace_id)
					values (:actorId, :actorName, :action, :companyId, :details, :traceId)""")
			.param("actorId", actorId)
			.param("actorName", actorName)
			.param("action", action)
			.param("companyId", companyId)
			.param("details", details != null && details.length() > 1000 ? details.substring(0, 1000) : details)
			.param("traceId", MDC.get(TraceIdFilter.MDC_KEY))
			.update();
	}

}
