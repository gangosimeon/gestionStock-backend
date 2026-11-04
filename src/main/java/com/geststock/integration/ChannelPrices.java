package com.geststock.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prix proposés par les boutiques liées, dans la base de l'<b>entreprise courante</b> (lot 3, étape G22a ; plan § 4 D2,
 * C6, v16-D3 ; tables {@code channel_price}, {@code channel_price_head}, {@code integration_idempotency}, V054).
 *
 * <ul>
 * <li><b>Proposition idempotente</b> : la réponse d'une proposition créée est enregistrée sous (liaison, opération,
 * clé {@code Idempotency-Key}), dans la transaction de la proposition ; la même clé et la même requête (empreinte de la
 * requête normalisée) renvoient cette réponse telle quelle ; la même clé et une autre requête :
 * {@code 409 IDEMPOTENCY_KEY_REUSED}. Une proposition refusée n'enregistre rien. Jamais purgé (plan § 36).</li>
 * <li><b>Refus sans rien créer</b>, dans cet ordre : produit inexistant (y compris d'une autre entreprise : il n'est
 * pas dans cette base) {@code 404 PRODUCT_NOT_FOUND} ; archivé {@code 422 PRODUCT_ARCHIVED} ; autre devise que celle
 * de la liaison {@code 422 PRICE_CURRENCY_MISMATCH} ; version du prix de référence différente de
 * {@code product.version} {@code 422 PRICE_VERSION_MISMATCH}.</li>
 * <li><b>Concurrence (C6)</b> : la ligne {@code channel_price_head} de (liaison, produit) est verrouillée
 * ({@code FOR UPDATE}) ; sous ce verrou, la proposition {@code PROPOSED} précédente passe {@code SUPERSEDED}, le rang
 * {@code price_version} avance et la nouvelle est insérée : la dernière qui obtient le verrou est retenue, jamais
 * déduite d'un horodatage applicatif. L'index unique partiel garantit au plus une {@code PROPOSED}.</li>
 * <li><b>Lecture</b> : seules les propositions de la liaison demandée (jamais celles d'une autre liaison).</li>
 * <li><b>Décision</b> (G22b ; P9, P10) par un utilisateur GestStock : sous le même verrou de tête que les propositions
 * (même ordre de verrous), mise à jour conditionnée par {@code status = 'PROPOSED'} ; aucune ligne modifiée :
 * {@code 409 PRICE_PROPOSAL_NOT_PENDING}, rien n'est approuvé à tort. L'approbation fige le prix de référence courant
 * ({@code approved_reference_price}, base de {@code referenceChangedSinceApproval}) ; le précédent prix approuvé reste
 * dans l'historique. Chaque décision avance le rang de la tête, est tracée dans le journal d'audit de l'entreprise
 * (sans le motif ni la note : seule la trace de la ligne les porte) et publie l'événement mince {@code CHANNEL_PRICE}
 * (P2), recalculé pour chaque liaison qui lit le flux.</li>
 * </ul>
 * Aucune valeur reçue (prix, note, clé) n'est journalisée, y compris par la trace des paramètres de Spring JDBC
 * ({@link MaskedValue}).
 */
@Component
public class ChannelPrices {

	/** Opération enregistrée sous la clé d'idempotence (contrat : {@code operationId}). */
	static final String PROPOSE = "createPriceProposal";

	private static final String PRODUCT = "select retail_price, version, active from product where id = :id";

	private static final String PRODUCTS = "select id, retail_price, version from product where id in (:ids)";

	private static final String REPLAY = """
			select request_hash, response_body from integration_idempotency
			where link_id = :link and operation = :operation and idempotency_key = :key""";

	private static final String HEAD = """
			insert into channel_price_head (link_id, product_id) values (:link, :product)
			on conflict (link_id, product_id) do nothing""";

	private static final String LOCK_HEAD = """
			select price_version from channel_price_head where link_id = :link and product_id = :product for update""";

	private static final String SUPERSEDE = """
			update channel_price set status = 'SUPERSEDED', decided_at = now()
			where link_id = :link and product_id = :product and status = 'PROPOSED'""";

	private static final String ADVANCE_HEAD = """
			update channel_price_head set price_version = :version, updated_at = now()
			where link_id = :link and product_id = :product""";

	private static final String INSERT = """
			insert into channel_price (id, link_id, product_id, price_version, status, price, currency,
			  based_on_reference_version, reference_price, note)
			values (:id, :link, :product, :version, 'PROPOSED', :price, :currency, :basedOn, :referencePrice, :note)""";

	private static final String REMEMBER = """
			insert into integration_idempotency (link_id, operation, idempotency_key, request_hash, response_status,
			  response_body)
			values (:link, :operation, :key, :hash, :status, :body)""";

	private static final String APPROVED = """
			select price, currency, price_version, decided_at, approved_reference_price from channel_price
			where link_id = :link and product_id = :product and status = 'APPROVED'
			order by price_version desc limit 1""";

	private static final String LATEST = """
			select id, price, status, rejection_reason, price_version from channel_price
			where link_id = :link and product_id = :product
			order by price_version desc limit 1""";

	private final JdbcClient jdbc;

	private final TransactionTemplate readWrite;

	private final TransactionTemplate readOnly;

	private static final String DECISION_TARGET = """
			select product_id from channel_price where id = :id and link_id = :link""";

	private static final String APPROVE = """
			update channel_price set status = 'APPROVED', decided_at = now(), decided_by = :user,
			  approved_reference_price = (select p.retail_price from product p where p.id = channel_price.product_id)
			where id = :id and status = 'PROPOSED'""";

	private static final String REJECT = """
			update channel_price set status = 'REJECTED', decided_at = now(), decided_by = :user,
			  rejection_reason = :reason
			where id = :id and status = 'PROPOSED'""";

	private static final String ADVANCE_ON_DECISION = """
			update channel_price_head set price_version = price_version + 1, updated_at = now()
			where link_id = :link and product_id = :product""";

	private static final String VIEW_COLUMNS = """
			select c.id, c.product_id, p.name, p.sku, c.price, c.currency, c.status, c.reference_price,
			  p.retail_price, c.note, c.proposed_at, c.decided_at, c.rejection_reason
			from channel_price c join product p on p.id = c.product_id
			""";

	private static final String VIEW = VIEW_COLUMNS + "where c.id = :id";

	private static final String LIST = VIEW_COLUMNS + """
			where c.link_id = :link and c.status = :status
			order by c.proposed_at desc, c.id
			limit :limit offset :offset""";

	private static final String COUNT = """
			select count(*) from channel_price where link_id = :link and status = :status""";

	private final JsonMapper json;

	private final IntegrationEvents events;

	private final AuditService audit;

	ChannelPrices(DataSource dataSource, PlatformTransactionManager transactionManager, JsonMapper json,
			IntegrationEvents events, AuditService audit) {
		this.jdbc = JdbcClient.create(dataSource);
		this.readWrite = new TransactionTemplate(transactionManager);
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.json = json;
		this.events = events;
		this.audit = audit;
	}

	/**
	 * Proposition de prix de la liaison, ou réponse enregistrée d'une requête rejouée.
	 *
	 * @param linkCurrency   devise de la liaison (base plateforme)
	 * @param idempotencyKey clé {@code Idempotency-Key}, déjà validée
	 */
	public Outcome propose(UUID linkId, String linkCurrency, String idempotencyKey, ProposalRequest request) {
		String hash = request.hash();
		Optional<Outcome> replayed = replay(linkId, idempotencyKey, hash);
		if (replayed.isPresent()) {
			return replayed.get();
		}
		try {
			return this.readWrite.execute(status -> create(linkId, linkCurrency, idempotencyKey, hash, request));
		}
		catch (DuplicateKeyException ex) {
			// Même clé envoyée deux fois en même temps : la seconde transaction est annulée ; réponse de la première.
			return replay(linkId, idempotencyKey, hash).orElseThrow(() -> ex);
		}
	}

	/** Prix de la liaison pour ces produits de l'entreprise courante ; produits introuvables omis, ordre reçu. */
	public List<ChannelPrice> read(UUID linkId, List<UUID> productIds) {
		return this.readOnly.execute(status -> {
			List<UUID> ids = List.copyOf(new LinkedHashSet<>(productIds));
			List<ProductRow> products = this.jdbc.sql(PRODUCTS).param("ids", ids)
				.query((rs, n) -> new ProductRow(rs.getObject("id", UUID.class), rs.getBigDecimal("retail_price"),
						rs.getLong("version"), true))
				.list();
			List<ChannelPrice> prices = new ArrayList<>();
			for (UUID id : ids) {
				products.stream().filter(product -> product.id().equals(id)).findFirst()
					.ifPresent(product -> prices.add(price(linkId, product)));
			}
			return prices;
		});
	}

	/**
	 * Propositions de la liaison dans ce statut, les plus récentes d'abord (approbateurs GestStock, G22b). La liaison
	 * doit appartenir à l'entreprise courante : vérifié par l'appelant (base plateforme).
	 */
	public Page<ProposalView> proposals(UUID linkId, String status, Pageable pageable) {
		return this.readOnly.execute(transaction -> {
			long total = this.jdbc.sql(COUNT).param("link", linkId).param("status", status).query(Long.class).single();
			List<ProposalView> items = this.jdbc.sql(LIST).param("link", linkId).param("status", status)
				.param("limit", pageable.getPageSize()).param("offset", pageable.getOffset()).query(this::view).list();
			return new PageImpl<>(items, pageable, total);
		});
	}

	/**
	 * Approuve une proposition encore en attente : elle devient le prix approuvé (seul commercialisable) de la liaison.
	 *
	 * @throws NotFoundException {@code PRICE_PROPOSAL_NOT_FOUND} : inconnue ou d'une autre liaison
	 * @throws ConflictException {@code PRICE_PROPOSAL_NOT_PENDING} : déjà décidée ou remplacée (C6)
	 */
	public ProposalView approve(UUID linkId, UUID proposalId, UUID userId) {
		return decide(linkId, proposalId, "PRICE_PROPOSAL_APPROVED", "approuvé",
				() -> this.jdbc.sql(APPROVE).param("user", userId).param("id", proposalId).update());
	}

	/**
	 * Refuse une proposition encore en attente, avec un motif (1 à 500 caractères, déjà nettoyé par l'appelant).
	 *
	 * @throws NotFoundException {@code PRICE_PROPOSAL_NOT_FOUND} : inconnue ou d'une autre liaison
	 * @throws ConflictException {@code PRICE_PROPOSAL_NOT_PENDING} : déjà décidée ou remplacée (C6)
	 */
	public ProposalView reject(UUID linkId, UUID proposalId, UUID userId, String reason) {
		return decide(linkId, proposalId, "PRICE_PROPOSAL_REJECTED", "refusé",
				() -> this.jdbc.sql(REJECT).param("user", userId).param("reason", MaskedValue.of(reason))
					.param("id", proposalId).update());
	}

	/** Décision sous le verrou de la tête du couple, dans l'ordre des verrous des propositions (C6). */
	private ProposalView decide(UUID linkId, UUID proposalId, String action, String verb,
			java.util.function.IntSupplier update) {
		return this.readWrite.execute(transaction -> {
			UUID productId = this.jdbc.sql(DECISION_TARGET).param("id", proposalId).param("link", linkId).query(UUID.class)
				.optional()
				.orElseThrow(() -> new NotFoundException(ErrorCode.PRICE_PROPOSAL_NOT_FOUND, null));
			this.jdbc.sql(LOCK_HEAD).param("link", linkId).param("product", productId).query(Long.class).single();
			if (update.getAsInt() == 0) {
				throw new ConflictException(ErrorCode.PRICE_PROPOSAL_NOT_PENDING, null);
			}
			this.jdbc.sql(ADVANCE_ON_DECISION).param("link", linkId).param("product", productId).update();
			ProposalView view = this.jdbc.sql(VIEW).param("id", proposalId).query(this::view).single();
			this.audit.record(AuditEvent.of(action, "CHANNEL_PRICE", proposalId, view.productName())
				.details("Prix proposé par une boutique en ligne " + verb + " : " + view.price() + " " + view.currency()
						+ " (prix de référence " + view.currentReferencePrice() + ")")
				.meta("linkId", linkId.toString()).meta("productId", productId.toString()));
			this.events.publish(IntegrationEventType.CHANNEL_PRICE, productId);
			return view;
		});
	}

	private ProposalView view(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
		return new ProposalView(rs.getObject("id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("name"),
				rs.getString("sku"), decimal(rs.getBigDecimal("price")), rs.getString("currency").trim(),
				rs.getString("status"), decimal(rs.getBigDecimal("reference_price")), decimal(rs.getBigDecimal("retail_price")),
				rs.getString("note"), rs.getObject("proposed_at", OffsetDateTime.class).toInstant(),
				decidedAt != null ? decidedAt.toInstant() : null, rs.getString("rejection_reason"));
	}

	private Optional<Outcome> replay(UUID linkId, String key, String hash) {
		return this.jdbc.sql(REPLAY).param("link", linkId).param("operation", PROPOSE).param("key", MaskedValue.of(key))
			.query((rs, n) -> new String[] { rs.getString("request_hash"), rs.getString("response_body") })
			.optional()
			.map(stored -> {
				if (!stored[0].equals(hash)) {
					throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, null);
				}
				return new Outcome(false, this.json.readTree(stored[1]));
			});
	}

	private Outcome create(UUID linkId, String linkCurrency, String key, String hash, ProposalRequest request) {
		UUID productId = request.productId();
		ProductRow product = this.jdbc.sql(PRODUCT).param("id", productId)
			.query((rs, n) -> new ProductRow(productId, rs.getBigDecimal("retail_price"), rs.getLong("version"),
					rs.getBoolean("active")))
			.optional()
			.orElseThrow(() -> new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, null));
		if (!product.active()) {
			throw new BusinessRuleException(ErrorCode.PRODUCT_ARCHIVED, null);
		}
		if (!request.currency().equals(linkCurrency)) {
			throw new BusinessRuleException(ErrorCode.PRICE_CURRENCY_MISMATCH, null);
		}
		if (request.basedOnReferenceVersion() != product.version()) {
			throw new BusinessRuleException(ErrorCode.PRICE_VERSION_MISMATCH, null);
		}
		// C6 : propositions du couple sérialisées par le verrou de sa ligne de tête.
		this.jdbc.sql(HEAD).param("link", linkId).param("product", productId).update();
		long version = this.jdbc.sql(LOCK_HEAD).param("link", linkId).param("product", productId).query(Long.class).single()
				+ 1;
		this.jdbc.sql(SUPERSEDE).param("link", linkId).param("product", productId).update();
		this.jdbc.sql(ADVANCE_HEAD).param("version", version).param("link", linkId).param("product", productId).update();
		this.jdbc.sql(INSERT).param("id", UUID.randomUUID()).param("link", linkId).param("product", productId)
			.param("version", version).param("price", MaskedValue.of(request.price())).param("currency", request.currency())
			.param("basedOn", request.basedOnReferenceVersion()).param("referencePrice", product.referencePrice())
			.param("note", MaskedValue.of(request.note())).update();
		String body = this.json.writeValueAsString(price(linkId, product));
		this.jdbc.sql(REMEMBER).param("link", linkId).param("operation", PROPOSE).param("key", MaskedValue.of(key))
			.param("hash", hash).param("status", 201).param("body", MaskedValue.of(body)).update();
		return new Outcome(true, this.json.readTree(body));
	}

	/** Prix de la liaison pour le produit : dernier approuvé, dernière proposition (si elle n'est pas celle-là). */
	private ChannelPrice price(UUID linkId, ProductRow product) {
		Optional<ApprovedRow> approved = this.jdbc.sql(APPROVED).param("link", linkId).param("product", product.id())
			.query((rs, n) -> new ApprovedRow(rs.getBigDecimal("price"), rs.getString("currency"), rs.getLong("price_version"),
					rs.getObject("decided_at", OffsetDateTime.class), rs.getBigDecimal("approved_reference_price")))
			.optional();
		Optional<LatestRow> latest = this.jdbc.sql(LATEST).param("link", linkId).param("product", product.id())
			.query((rs, n) -> new LatestRow(rs.getObject("id", UUID.class), rs.getBigDecimal("price"), rs.getString("status"),
					rs.getString("rejection_reason"), rs.getLong("price_version")))
			.optional();
		ChannelPrice.Approved approvedView = approved.map(row -> new ChannelPrice.Approved(decimal(row.price()),
				row.currency().trim(), row.priceVersion(), row.decidedAt().toInstant())).orElse(null);
		ChannelPrice.Pending pending = latest
			.filter(row -> approved.isEmpty() || row.priceVersion() != approved.get().priceVersion())
			.map(row -> new ChannelPrice.Pending(row.id(), decimal(row.price()), row.status(), row.rejectionReason()))
			.orElse(null);
		boolean referenceChanged = approved
			.map(row -> row.referencePrice().compareTo(product.referencePrice()) != 0).orElse(false);
		return new ChannelPrice(product.id(), decimal(product.referencePrice()), product.version(), approvedView, pending,
				referenceChanged);
	}

	/** Décimal en chaîne, deux décimales (contrat : {@code "6000.00"}), comme la colonne numeric(14,2). */
	static String decimal(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	/**
	 * Proposition reçue, déjà validée par la route.
	 *
	 * @param price prix proposé, positif, deux décimales au plus
	 * @param note  note libre de la boutique ({@code null} si absente), 500 caractères au plus
	 */
	public record ProposalRequest(UUID productId, BigDecimal price, String currency, long basedOnReferenceVersion,
			String note) {

		/** Empreinte de la requête normalisée (prix à deux décimales) : base de la comparaison des requêtes rejouées. */
		String hash() {
			String canonical = this.productId + "\n" + decimal(this.price) + "\n" + this.currency + "\n"
					+ this.basedOnReferenceVersion + "\n" + (this.note != null ? "1" + this.note : "0");
			try {
				return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonical.getBytes(StandardCharsets.UTF_8)));
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException(ex);
			}
		}

		@Override
		public String toString() {
			return "ProposalRequest[productId=" + this.productId + "]";
		}

	}

	/**
	 * @param created vrai : proposition créée ({@code 201}) ; faux : requête rejouée ({@code 200})
	 * @param body    réponse {@code ChannelPrice}, telle qu'enregistrée
	 */
	public record Outcome(boolean created, JsonNode body) {
	}

	/**
	 * Proposition vue par un approbateur GestStock (G22b).
	 *
	 * @param proposalId                proposition
	 * @param productId                 produit
	 * @param productName               nom du produit
	 * @param productSku                code (SKU) du produit
	 * @param price                     prix proposé, deux décimales
	 * @param currency                  devise de la liaison
	 * @param status                    statut de la proposition
	 * @param referencePriceAtProposal  prix de référence au moment de la proposition
	 * @param currentReferencePrice     prix de référence courant
	 * @param note                      note de la boutique ({@code null} si absente)
	 * @param proposedAt                date de la proposition
	 * @param decidedAt                 date de la décision ({@code null} si en attente)
	 * @param rejectionReason           motif du refus ({@code null} sinon)
	 */
	public record ProposalView(UUID proposalId, UUID productId, String productName, String productSku, String price,
			String currency, String status, String referencePriceAtProposal, String currentReferencePrice, String note,
			Instant proposedAt, Instant decidedAt, String rejectionReason) {

		@Override
		public String toString() {
			return "ProposalView[proposalId=" + this.proposalId + ", status=" + this.status + "]";
		}

	}

	private record ProductRow(UUID id, BigDecimal referencePrice, long version, boolean active) {
	}

	private record ApprovedRow(BigDecimal price, String currency, long priceVersion, OffsetDateTime decidedAt,
			BigDecimal referencePrice) {
	}

	private record LatestRow(UUID id, BigDecimal price, String status, String rejectionReason, long priceVersion) {
	}

}
