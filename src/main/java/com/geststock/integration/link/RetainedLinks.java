package com.geststock.integration.link;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;

/**
 * Liaisons qui retiennent le flux d'une entreprise (plan § 30.4, étape 2 ; D5, v17-D4) : ses liaisons {@code ACTIVE}
 * ou {@code SUSPENDED} de la base plateforme. Les liaisons révoquées sont exclues ; une liaison en attente n'a jamais
 * lu le flux. Composant distinct pour que l'ordre « plafond, puis liaisons, puis acquittements » de la purge
 * (v17-D11) soit observable et testable. Les mesures de la maintenance (G20c) lisent leurs états à part
 * ({@link #states}).
 */
@Component
public class RetainedLinks {

	private static final String RETAINED = """
			select id from platform.integration_link
			where company_id = :company and status in ('ACTIVE', 'SUSPENDED')""";

	private static final String STATES = """
			select id, status,
			       floor(extract(epoch from now() - activated_at))::bigint as since_activation_seconds,
			       case when status = 'SUSPENDED' then floor(extract(epoch from now() - suspended_at))::bigint
			            else 0 end as since_suspension_seconds
			from platform.integration_link
			where company_id = :company and status in ('ACTIVE', 'SUSPENDED')
			order by id""";

	private final PlatformDb platform;

	RetainedLinks(PlatformDb platform) {
		this.platform = platform;
	}

	/** Liaisons actives ou suspendues de l'entreprise. */
	public Set<UUID> of(UUID companyId) {
		return Set.copyOf(this.platform.jdbc().sql(RETAINED).param("company", companyId).query(UUID.class).list());
	}

	/**
	 * Liaisons actives ou suspendues de l'entreprise, avec leurs âges calculés par la base plateforme (mesures de la
	 * maintenance, G20c ; plan § 33.5) ; triées par identifiant.
	 */
	public List<RetainedLink> states(UUID companyId) {
		return this.platform.jdbc().sql(STATES).param("company", companyId)
			.query((rs, row) -> new RetainedLink(rs.getObject("id", UUID.class), LinkStatus.valueOf(rs.getString("status")),
					rs.getLong("since_activation_seconds"), rs.getLong("since_suspension_seconds")))
			.list();
	}

}
