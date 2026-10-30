package com.geststock.integration.link;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.integration.PublicationFlag;
import com.geststock.platform.PlatformDb;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

/**
 * Drapeau de publication d'une entreprise tenu depuis ses liaisons (lot 2, étape G13 ; v12-D4, v14-D11), l'entreprise
 * étant toujours donnée explicitement (jamais lue dans le contexte courant).
 * <ul>
 * <li>{@link #open} avant l'activation d'une liaison (échange du code) : aucune fenêtre ne s'ouvre avant le premier
 * instantané ; si l'activation échoue ensuite, {@link #synchronize} le remet à sa valeur ;</li>
 * <li>{@link #synchronize} après le commit d'une révocation, au mieux ; aussi pour la tâche planifiée du lot 3.</li>
 * </ul>
 */
@Component
class LinkPublication {

	private static final String LINKED = """
			select exists (select 1 from platform.integration_link where company_id = :company
			               and status in ('ACTIVE', 'SUSPENDED'))""";

	private final PlatformDb platform;

	private final PublicationFlag flag;

	LinkPublication(PlatformDb platform, PublicationFlag flag) {
		this.platform = platform;
		this.flag = flag;
	}

	/** Lève le drapeau de l'entreprise (attend la fin des transactions qui l'ont lu à faux). */
	void open(UUID companyId) {
		TenantContext.runAs(company(companyId), () -> this.flag.set(true));
	}

	/**
	 * Idempotente : drapeau levé si l'entreprise a au moins une liaison active ou suspendue, baissé sinon.
	 * @return la valeur posée
	 */
	boolean synchronize(UUID companyId) {
		boolean linked = this.platform.jdbc().sql(LINKED).param("company", companyId).query(Boolean.class).single();
		TenantContext.runAs(company(companyId), () -> this.flag.set(linked));
		return linked;
	}

	private TenantRef company(UUID companyId) {
		return new TenantRef(companyId, this.platform.jdbc().sql("select code from platform.company where id = :id")
			.param("id", companyId).query(String.class).single());
	}

}
