package com.geststock.integration.link;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.geststock.integration.CursorAheadException;
import com.geststock.integration.IntegrationFeedStateMissingException;

/**
 * Acquittements du flux par liaison, dans la base de l'<b>entreprise courante</b> ({@code integration_link_ack},
 * v17-D3) : une ligne par liaison, le plus grand {@code seq} acquitté, jamais en recul (v17-D13).
 */
@Component
public class LinkAcknowledgements {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final String LAST_SEQ = "select last_seq from integration_feed_state where id = cast(:id as uuid)";

	/** Valeur enregistrée = max(enregistrée, reçue) ; date du dernier acquittement accepté, même sans progression. */
	private static final String ACKNOWLEDGE = """
			insert into integration_link_ack as a (link_id, acknowledged_seq, acknowledged_at)
			values (:link, :seq, now())
			on conflict (link_id) do update
			  set acknowledged_seq = greatest(a.acknowledged_seq, excluded.acknowledged_seq), acknowledged_at = now()
			returning a.acknowledged_seq""";

	private static final String OF = """
			select link_id, acknowledged_seq from integration_link_ack where link_id in (:links)""";

	private final JdbcClient jdbc;

	LinkAcknowledgements(DataSource dataSource) {
		this.jdbc = JdbcClient.create(dataSource);
	}

	/**
	 * Acquitte {@code seq} pour la liaison : une seule instruction atomique, jamais de recul ; un {@code seq} inférieur
	 * ou égal à la valeur enregistrée, même sous le repère de purge, la laisse inchangée (v17-D13).
	 *
	 * @return valeur enregistrée après l'appel
	 * @throws CursorAheadException                {@code seq} supérieur au dernier {@code seq} attribué ; rien n'est
	 *                                             enregistré (v17-D6)
	 * @throws IntegrationFeedStateMissingException ligne d'état du flux absente
	 */
	public long acknowledge(UUID linkId, long seq) {
		if (seq < 0) {
			throw new IllegalArgumentException("Acquittement négatif");
		}
		// last_seq ne décroît jamais : un seq qui ne le dépasse pas maintenant ne le dépassera jamais.
		long lastSeq = this.jdbc.sql(LAST_SEQ).param("id", FEED_STATE_ID).query(Long.class).optional()
			.orElseThrow(IntegrationFeedStateMissingException::new);
		if (seq > lastSeq) {
			throw new CursorAheadException(seq, lastSeq);
		}
		return this.jdbc.sql(ACKNOWLEDGE).param("link", linkId).param("seq", seq).query(Long.class).single();
	}

	/** Acquittements enregistrés de ces liaisons ; une liaison sans acquittement est absente. */
	public Map<UUID, Long> of(Set<UUID> linkIds) {
		Map<UUID, Long> acknowledged = new HashMap<>();
		if (linkIds.isEmpty()) {
			return acknowledged;
		}
		this.jdbc.sql(OF).param("links", linkIds)
			.query((rs, row) -> acknowledged.put(rs.getObject("link_id", UUID.class), rs.getLong("acknowledged_seq")))
			.list();
		return acknowledged;
	}

}
