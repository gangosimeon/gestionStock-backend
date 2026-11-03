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
 *
 * <p><b>Échange de code concurrent</b> (lot 3, G20f-1) : l'échange lève le drapeau, puis active la liaison. Une
 * resynchronisation entre les deux ne doit pas le baisser : les liaisons sont lues une fois le verrou du drapeau obtenu
 * ({@link PublicationFlag#synchronize(java.util.function.BooleanSupplier)}), et une liaison en attente dont le code est
 * encore utilisable (non utilisé, expiré depuis moins de {@value #EXCHANGE_GRACE}) compte comme liée. Le drapeau peut
 * donc rester levé quelques minutes de trop : sans danger (événements en trop, séquencés puis purgés).
 * L'échange qui échoue ignore sa propre liaison : le drapeau revient à sa valeur.
 *
 * <p>{@link #raiseIfLinked} (maintenance, G20f-1) ne fait que lever le drapeau, sans verrou si rien n'est à faire.
 */
@Component
class LinkPublication {

	/** Marge après l'expiration du code : un échange commencé juste avant reste couvert. */
	static final String EXCHANGE_GRACE = "1 minute";

	/** Liaison active ou suspendue, ou en attente avec un code encore utilisable (sauf la liaison ignorée). */
	private static final String LINKED = """
			select exists (select 1 from platform.integration_link l where l.company_id = :company
			               and (l.status in ('ACTIVE', 'SUSPENDED')
			                    or (l.status = 'PENDING' and l.id is distinct from cast(:ignored as uuid)
			                        and exists (select 1 from platform.integration_authorization_code c
			                                    where c.link_id = l.id and c.used_at is null
			                                      and c.expires_at > now() - interval '""" + EXCHANGE_GRACE + "'))))";

	/** Liaison active ou suspendue : seule raison de lever le drapeau par la maintenance. */
	private static final String RETAINED = """
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
	 * Idempotente : drapeau levé si l'entreprise a au moins une liaison active ou suspendue (ou un échange de code
	 * possible), baissé sinon ; liaisons lues sous le verrou du drapeau.
	 * @return la valeur posée
	 */
	boolean synchronize(UUID companyId) {
		return synchronize(companyId, null);
	}

	/**
	 * Comme {@link #synchronize(UUID)}, sans compter la liaison en attente {@code ignoredPendingLink} : celle d'un
	 * échange qui vient d'échouer.
	 */
	boolean synchronize(UUID companyId, UUID ignoredPendingLink) {
		return TenantContext.callAs(company(companyId), () -> this.flag.synchronize(() -> this.platform.jdbc().sql(LINKED)
			.param("company", companyId).param("ignored", ignoredPendingLink).query(Boolean.class).single()));
	}

	/**
	 * Lève le drapeau s'il est baissé alors que l'entreprise a une liaison active ou suspendue ; ne le baisse jamais.
	 * Sans verrou si rien n'est à faire.
	 * @return vrai si le drapeau a été levé (des événements ont pu ne pas être publiés)
	 */
	boolean raiseIfLinked(UUID companyId) {
		TenantRef company = company(companyId);
		if (TenantContext.callAs(company, this.flag::peek)) {
			return false;
		}
		if (!this.platform.jdbc().sql(RETAINED).param("company", companyId).query(Boolean.class).single()) {
			return false;
		}
		TenantContext.runAs(company, () -> this.flag.set(true));
		return true;
	}

	private TenantRef company(UUID companyId) {
		return new TenantRef(companyId, this.platform.jdbc().sql("select code from platform.company where id = :id")
			.param("id", companyId).query(String.class).single());
	}

}
