package com.geststock.integration.link;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;

/**
 * Liaisons qui retiennent le flux d'une entreprise (plan § 30.4, étape 2 ; D5, v17-D4) : ses liaisons {@code ACTIVE}
 * ou {@code SUSPENDED} de la base plateforme. Les liaisons révoquées sont exclues ; une liaison en attente n'a jamais
 * lu le flux. Composant distinct pour que l'ordre « plafond, puis liaisons, puis acquittements » de la purge
 * (v17-D11) soit observable et testable.
 */
@Component
public class RetainedLinks {

	private static final String RETAINED = """
			select id from platform.integration_link
			where company_id = :company and status in ('ACTIVE', 'SUSPENDED')""";

	private final PlatformDb platform;

	RetainedLinks(PlatformDb platform) {
		this.platform = platform;
	}

	/** Liaisons actives ou suspendues de l'entreprise. */
	public Set<UUID> of(UUID companyId) {
		return Set.copyOf(this.platform.jdbc().sql(RETAINED).param("company", companyId).query(UUID.class).list());
	}

}
