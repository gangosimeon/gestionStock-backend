package com.geststock.integration.link;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.geststock.platform.PlatformDb;
import com.geststock.tenancy.TenantRef;

/**
 * Lecture de la liaison à chaque appel de la chaîne d'intégration (lot 2, étape G7) : une seule requête sur la base
 * plateforme, sans cache, pour que la révocation, la suspension ou le retrait d'une portée s'appliquent dès l'appel
 * suivant. Elle donne le statut de la liaison, le client, les portées, l'entreprise et son statut.
 */
@Component
public class LinkAccess {

	static final String STATE = """
			select l.status, l.scopes, cl.client_id as client_public_id, c.id as company_id, c.code as company_code,
			       c.status as company_status
			from platform.integration_link l
			join platform.integration_client cl on cl.id = l.client_id
			join platform.company c on c.id = l.company_id
			where l.id = :id""";

	private final PlatformDb platform;

	LinkAccess(PlatformDb platform) {
		this.platform = platform;
	}

	Optional<LinkState> load(UUID linkId) {
		return this.platform.jdbc().sql(STATE).param("id", linkId)
			.query((rs, n) -> new LinkState(LinkStatus.valueOf(rs.getString("status")), strings(rs.getArray("scopes")),
					rs.getString("client_public_id"), new TenantRef((UUID) rs.getObject("company_id"), rs.getString("company_code")),
					rs.getString("company_status")))
			.optional();
	}

	private static Set<String> strings(Array array) throws SQLException {
		return array != null ? new HashSet<>(Arrays.asList((String[]) array.getArray())) : Set.of();
	}

	/**
	 * État lu en base au moment de l'appel.
	 *
	 * @param status         statut de la liaison
	 * @param scopes         portées de la liaison
	 * @param clientId       identifiant public du client de la liaison
	 * @param company        entreprise de la liaison
	 * @param companyStatus  statut plateforme de l'entreprise ({@code ACTIVE}, {@code SUSPENDED}…)
	 */
	record LinkState(LinkStatus status, Set<String> scopes, String clientId, TenantRef company, String companyStatus) {
	}

}
