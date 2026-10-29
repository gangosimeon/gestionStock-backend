package com.geststock.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lecture du flux d'intégration (contrat {@code integration/v1}, {@code GET /catalog/changes} ; plan § 12 et
 * § 20) : produit une page à partir du curseur {@code after}. Service seul, sans route HTTP.
 *
 * <p>Déroulement :
 * <ol>
 * <li>validation : {@code after ≥ 0}, {@code limit} dans 1..500 (100 par défaut) ;</li>
 * <li>séquenceur ({@link FeedSequencer}), hors transaction : les événements validés avant l'appel reçoivent
 * leur {@code seq} et sont servis dans cette page. Un séquenceur <b>ignoré</b> (un autre travaille) n'est pas
 * une erreur : la page sert les {@code seq} déjà attribués ;</li>
 * <li>une transaction unique, courte, en lecture seule et en {@code REPEATABLE READ} : repère de purge,
 * {@code last_seq}, événements, disponibilités et {@code hasMore} viennent du <b>même instantané</b>.</li>
 * </ol>
 *
 * <p>Règles de la page : {@code after} sous le repère de purge → {@link CursorExpiredException} (v9-D1,
 * égalité acceptée) ; {@code after} au-delà de {@code last_seq} → {@link CursorAheadException}. Au plus
 * {@code limit} événements de {@code seq > after} sont <b>examinés</b> ; ceux que le filtre écarte font quand
 * même avancer {@code lastSeq} (P1). Les événements minces {@code AVAILABILITY} d'un même produit sont fusionnés
 * en celui de plus grand {@code seq}, dont la valeur est calculée à la lecture et la version est le {@code seq}
 * (P3). {@code hasMore} (P2) : un événement de {@code seq > lastSeq} ou un événement validé sans {@code seq}.
 *
 * <p>Un type stocké mais inconnu de ce code (écrit par une version plus récente, par exemple) ne bloque jamais
 * le flux : il est servi tel qu'il est stocké, soumis à {@link ChangeFilter#acceptsUnknown}, et journalisé en
 * WARN (type et {@code seq}, jamais la charge). Le consommateur ignore un type inconnu et avance son curseur.
 */
@Service
public class ChangeFeedService {

	private static final Logger log = LoggerFactory.getLogger(ChangeFeedService.class);

	static final int DEFAULT_LIMIT = 100;

	static final int MAX_LIMIT = 500;

	private static final String READ_STATE = """
			select last_seq, purged_through_seq from integration_feed_state where id = :id""";

	private static final String READ_EVENTS = """
			select seq, type, operation, object_id, object_version, payload::text as payload, created_at
			from integration_outbox
			where seq > :after
			order by seq
			limit :limit""";

	private static final String HAS_MORE = """
			select exists (select 1 from integration_outbox where seq > :lastSeq)
			    or exists (select 1 from integration_outbox where seq is null)""";

	private final FeedSequencer sequencer;

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final AvailabilityProvider availability;

	private final JsonMapper jsonMapper;

	private final ChangeFeedProperties properties;

	ChangeFeedService(FeedSequencer sequencer, DataSource dataSource, PlatformTransactionManager transactionManager,
			AvailabilityProvider availability, JsonMapper jsonMapper, ChangeFeedProperties properties) {
		this.sequencer = sequencer;
		this.jdbc = JdbcClient.create(dataSource);
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.transaction.setReadOnly(true);
		this.availability = availability;
		this.jsonMapper = jsonMapper;
		this.properties = properties;
	}

	/**
	 * Page du flux après {@code after}.
	 *
	 * @param after  dernier {@code seq} consommé (0 au départ)
	 * @param limit  nombre maximal d'événements examinés ; {@code null} : 100
	 * @param filter filtre de la liaison
	 * @throws ChangeFeedRequestException          {@code after < 0} ou {@code limit} hors de 1..500
	 *                                             ({@code VALIDATION_FAILED} ; un paramètre non numérique ou absent
	 *                                             est refusé avant, par la couche HTTP : {@code INVALID_PARAMETER})
	 * @throws CursorExpiredException              {@code after} inférieur au repère de purge
	 * @throws CursorAheadException                {@code after} supérieur au dernier {@code seq} attribué
	 * @throws IllegalTransactionStateException    appel depuis une transaction active
	 * @throws IntegrationFeedStateMissingException ligne d'état du flux absente
	 */
	public ChangePage read(long after, Integer limit, ChangeFilter filter) {
		Objects.requireNonNull(filter, "filter");
		if (after < 0) {
			throw new ChangeFeedRequestException("Curseur du flux invalide : after doit être positif ou nul.");
		}
		int effectiveLimit = limit != null ? limit : DEFAULT_LIMIT;
		if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
			throw new ChangeFeedRequestException("Taille de page invalide : limit doit être compris entre 1 et 500.");
		}
		this.sequencer.sequence();
		return this.transaction.execute(status -> page(after, effectiveLimit, filter));
	}

	private ChangePage page(long after, int limit, ChangeFilter filter) {
		// Valeur numérique issue de la configuration : SET n'accepte pas de paramètre lié.
		this.jdbc.sql("set local statement_timeout = " + this.properties.statementTimeout().toMillis()).update();
		FeedState state = this.jdbc.sql(READ_STATE)
			.param("id", FeedSequencer.FEED_STATE_ID)
			.query((rs, row) -> new FeedState(rs.getLong("last_seq"), rs.getLong("purged_through_seq")))
			.optional()
			.orElseThrow(IntegrationFeedStateMissingException::new);
		if (after < state.purgedThroughSeq()) {
			throw new CursorExpiredException(after, state.purgedThroughSeq());
		}
		if (after > state.lastSeq()) {
			throw new CursorAheadException(after, state.lastSeq());
		}

		List<StoredEvent> examined = this.jdbc.sql(READ_EVENTS)
			.param("after", after)
			.param("limit", limit)
			.query(this::storedEvent)
			.list();
		long lastSeq = examined.isEmpty() ? after : examined.getLast().seq();
		List<StoredEvent> accepted = examined.stream().filter(e -> e.acceptedBy(filter)).toList();

		// Fusion des événements minces : seul le plus grand seq de chaque objet est servi.
		Map<UUID, Long> latestThin = new HashMap<>();
		accepted.stream().filter(StoredEvent::isThin).forEach(e -> latestThin.put(e.objectId(), e.seq()));
		Map<UUID, JsonNode> thinValues = thinValues(accepted, latestThin);
		List<ChangeEvent> items = new ArrayList<>();
		for (StoredEvent event : accepted) {
			if (!event.isThin()) {
				items.add(event.toChange(event.objectVersion(), event.payload()));
			}
			else if (latestThin.get(event.objectId()) == event.seq()) {
				items.add(event.toChange(event.seq(), thinValues.get(event.objectId())));
			}
		}

		boolean hasMore = this.jdbc.sql(HAS_MORE).param("lastSeq", lastSeq).query(Boolean.class).single();
		return new ChangePage(items, hasMore, lastSeq);
	}

	/** Valeurs des événements minces servis, en <b>un seul appel</b> du fournisseur pour toute la page. */
	private Map<UUID, JsonNode> thinValues(List<StoredEvent> accepted, Map<UUID, Long> latestThin) {
		accepted.stream().filter(StoredEvent::isThin).forEach(event -> {
			if (event.type() != IntegrationEventType.AVAILABILITY) {
				throw new IllegalStateException("Événement mince sans calcul à la lecture : " + event.type());
			}
		});
		if (latestThin.isEmpty()) {
			return Map.of();
		}
		Map<UUID, ?> values = this.availability.availability(Set.copyOf(latestThin.keySet()));
		Map<UUID, JsonNode> nodes = new HashMap<>();
		latestThin.keySet().forEach(productId -> nodes.put(productId, this.jsonMapper.valueToTree(
				Objects.requireNonNull(values.get(productId), () -> "disponibilité absente pour " + productId))));
		return nodes;
	}

	private StoredEvent storedEvent(ResultSet rs, int row) throws SQLException {
		long seq = rs.getLong("seq");
		String typeName = rs.getString("type");
		IntegrationEventType type = knownType(typeName);
		if (type == null) {
			log.warn("Flux d'intégration : type d'événement inconnu de ce code, servi tel quel (type={}, seq={})",
					typeName, seq);
		}
		String payload = rs.getString("payload");
		return new StoredEvent(seq, type, typeName, IntegrationOperation.valueOf(rs.getString("operation")),
				rs.getObject("object_id", UUID.class), rs.getLong("object_version"),
				payload != null ? this.jsonMapper.readTree(payload) : null, rs.getObject("created_at", OffsetDateTime.class));
	}

	private static IntegrationEventType knownType(String typeName) {
		for (IntegrationEventType type : IntegrationEventType.values()) {
			if (type.name().equals(typeName)) {
				return type;
			}
		}
		return null;
	}

	private record FeedState(long lastSeq, long purgedThroughSeq) {
	}

	/** Ligne du flux ; {@code type} vaut {@code null} si {@code typeName} est inconnu de ce code. */
	private record StoredEvent(long seq, IntegrationEventType type, String typeName, IntegrationOperation operation,
			UUID objectId, long objectVersion, JsonNode payload, OffsetDateTime createdAt) {

		boolean isThin() {
			return this.type != null && this.type.isThin();
		}

		boolean acceptedBy(ChangeFilter filter) {
			return this.type != null ? filter.accepts(this.type, this.objectId)
					: filter.acceptsUnknown(this.typeName, this.objectId);
		}

		ChangeEvent toChange(long version, JsonNode data) {
			return new ChangeEvent(this.seq, this.type, this.typeName, this.objectId, version, this.operation,
					this.createdAt.toInstant(), data);
		}

	}

}
