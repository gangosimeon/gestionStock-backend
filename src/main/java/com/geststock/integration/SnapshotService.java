package com.geststock.integration;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Instantané paginé et cohérent du catalogue (contrat {@code integration/v1}, {@code GET /catalog/snapshot} ;
 * décisions C3, P4, v9-D2, v11-D10). Service seul, sans route HTTP.
 *
 * <p>Première page (sans {@code pageToken}) : la devise est lue dans la base plateforme, puis le séquenceur passe
 * (hors transaction), puis une transaction {@code REPEATABLE READ} en lecture seule lit d'abord {@code asOfSeq}
 * ({@code last_seq} validé) et ensuite les objets. Pages suivantes : {@code asOfSeq}, heure et devise de la première
 * page et position sont repris du jeton vérifié ({@link PageTokenCodec}), sans relire la base plateforme ; chaque
 * page a sa propre transaction
 * {@code REPEATABLE READ} en lecture seule. Un objet modifié entre deux pages peut apparaître dans une version
 * postérieure à {@code asOfSeq} : le consommateur applique la règle des versions puis reprend le flux après
 * {@code asOfSeq}, ce qui reconstruit l'état source.
 *
 * <p>Sections dans l'ordre {@code categories}, {@code products}, {@code productImages}, {@code availability} ;
 * pagination par clé ({@code id} croissant) dans chaque section ; {@code limit} (200 par défaut, 500 au plus)
 * compte les objets toutes sections confondues, une page peut chevaucher deux sections. Objets archivés inclus.
 * Versions : {@code @Version} (catégories, produits, même mappeur que les événements), génération de l'image ou 0,
 * {@code asOfSeq} pour la disponibilité (calculée par page en un appel du fournisseur).
 */
@Service
public class SnapshotService {

	static final int DEFAULT_LIMIT = 200;

	static final int MAX_LIMIT = 500;

	private static final UUID START = new UUID(0L, 0L);

	private static final String READ_LAST_SEQ = """
			select last_seq from integration_feed_state where id = :id""";

	private final Map<SnapshotSection, SnapshotSource> sources = new EnumMap<>(SnapshotSection.class);

	private final CatalogProductIds productIds;

	private final OutboxWriter integrationTables;

	private final AvailabilityProvider availability;

	private final FeedSequencer sequencer;

	private final PageTokenCodec tokens;

	private final CompanyCurrency currency;

	private final JsonMapper jsonMapper;

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	private final ChangeFeedProperties properties;

	private final Clock clock;

	SnapshotService(List<SnapshotSource> sources, CatalogProductIds productIds, OutboxWriter integrationTables,
			AvailabilityProvider availability, FeedSequencer sequencer, PageTokenCodec tokens, CompanyCurrency currency,
			JsonMapper jsonMapper, DataSource dataSource, PlatformTransactionManager transactionManager,
			ChangeFeedProperties properties, Clock clock) {
		sources.forEach(source -> {
			if (this.sources.put(source.section(), source) != null) {
				throw new IllegalStateException("Deux sources pour la section " + source.section());
			}
		});
		for (SnapshotSection section : List.of(SnapshotSection.CATEGORIES, SnapshotSection.PRODUCTS)) {
			if (!this.sources.containsKey(section)) {
				throw new IllegalStateException("Aucune source pour la section " + section);
			}
		}
		this.productIds = productIds;
		this.integrationTables = integrationTables;
		this.availability = availability;
		this.sequencer = sequencer;
		this.tokens = tokens;
		this.currency = currency;
		this.jsonMapper = jsonMapper;
		this.jdbc = JdbcClient.create(dataSource);
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		this.transaction.setReadOnly(true);
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Page de l'instantané.
	 *
	 * @param link      liaison qui lit (le jeton lui est lié)
	 * @param pageToken {@code null} pour la première page, sinon le {@code nextPageToken} reçu
	 * @param limit     nombre maximal d'objets, toutes sections confondues ; {@code null} : 200
	 * @throws SnapshotRequestException             {@code limit} hors de 1..500 ({@code VALIDATION_FAILED})
	 * @throws InvalidPageTokenException            jeton illisible, altéré, clé inconnue ou autre liaison
	 * @throws SnapshotExpiredException             jeton de plus de 24 heures
	 * @throws UnsupportedCurrencyException         devise de l'entreprise sans code ISO 4217 connu
	 * @throws IllegalTransactionStateException     appel depuis une transaction active (première page)
	 * @throws IntegrationFeedStateMissingException ligne d'état du flux absente
	 */
	public SnapshotPage read(LinkRef link, String pageToken, Integer limit) {
		Objects.requireNonNull(link, "link");
		int effectiveLimit = limit != null ? limit : DEFAULT_LIMIT;
		if (effectiveLimit < 1 || effectiveLimit > MAX_LIMIT) {
			throw new SnapshotRequestException("Taille de page invalide : limit doit être compris entre 1 et 500.");
		}
		PageToken position = pageToken != null ? this.tokens.decode(pageToken, link) : null;
		// Devise fixée à la première page et portée par le jeton : la base plateforme (autre base, autre pool) n'est
		// lue qu'une fois, avant d'ouvrir la transaction de page de l'entreprise.
		String isoCurrency = position != null ? position.currency() : this.currency.isoCode();
		if (position == null) {
			this.sequencer.sequence();
		}
		return this.transaction.execute(status -> page(link, position, effectiveLimit, isoCurrency));
	}

	private SnapshotPage page(LinkRef link, PageToken position, int limit, String isoCurrency) {
		this.jdbc.sql("set local statement_timeout = " + this.properties.statementTimeout().toMillis()).update();
		long asOfSeq;
		Instant issuedAt;
		SnapshotSection section;
		UUID lastId;
		if (position == null) {
			asOfSeq = this.jdbc.sql(READ_LAST_SEQ).param("id", FeedSequencer.FEED_STATE_ID).query(Long.class).optional()
				.orElseThrow(IntegrationFeedStateMissingException::new);
			issuedAt = this.clock.instant();
			section = SnapshotSection.CATEGORIES;
			lastId = START;
		}
		else {
			asOfSeq = position.asOfSeq();
			issuedAt = position.issuedAt();
			section = position.section();
			lastId = position.lastId();
		}

		Map<SnapshotSection, List<JsonNode>> content = new EnumMap<>(SnapshotSection.class);
		for (SnapshotSection each : SnapshotSection.values()) {
			content.put(each, new ArrayList<>());
		}
		int remaining = limit;
		while (section != null && remaining > 0) {
			List<SnapshotItem> items = items(section, lastId, remaining);
			List<JsonNode> served = content.get(section);
			items.forEach(item -> served.add(this.jsonMapper.valueToTree(item.data())));
			if (items.size() < remaining) {
				section = next(section);
				lastId = START;
			}
			else {
				lastId = items.getLast().id();
			}
			remaining -= items.size();
		}
		String nextPageToken = section == null ? null
				: this.tokens.encode(new PageToken(link, asOfSeq, issuedAt, isoCurrency, section, lastId));
		return new SnapshotPage(asOfSeq, isoCurrency, content.get(SnapshotSection.CATEGORIES),
				content.get(SnapshotSection.PRODUCTS), content.get(SnapshotSection.PRODUCT_IMAGES),
				content.get(SnapshotSection.AVAILABILITY), nextPageToken);
	}

	/** Au plus {@code limit} objets de la section après {@code lastId}, par identifiant croissant. */
	private List<SnapshotItem> items(SnapshotSection section, UUID lastId, int limit) {
		return switch (section) {
			case CATEGORIES, PRODUCTS -> this.sources.get(section).after(lastId, limit);
			case PRODUCT_IMAGES -> {
				List<UUID> ids = this.productIds.idsAfter(lastId, limit);
				Map<UUID, ProductImageState> states = this.integrationTables.imageStates(ids);
				yield ids.stream()
					.map(id -> new SnapshotItem(id, states.getOrDefault(id, new ProductImageState(id, null, 0L))))
					.toList();
			}
			case AVAILABILITY -> {
				List<UUID> ids = this.productIds.idsAfter(lastId, limit);
				Map<UUID, ?> values = ids.isEmpty() ? Map.of() : this.availability.availability(new LinkedHashSet<>(ids));
				yield ids.stream()
					.map(id -> new SnapshotItem(id, Objects.requireNonNull(values.get(id), () -> "disponibilité absente pour " + id)))
					.toList();
			}
		};
	}

	private static SnapshotSection next(SnapshotSection section) {
		SnapshotSection[] sections = SnapshotSection.values();
		return section.ordinal() + 1 < sections.length ? sections[section.ordinal() + 1] : null;
	}

}
