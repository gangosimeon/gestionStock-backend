package com.geststock.integration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;

/**
 * Gestion des commandes en ligne reçues des boutiques liées (lot 3, étape G28 ;
 * tables {@code online_order}, {@code online_order_line}, {@code online_reservation},
 * {@code online_reservation_line}, V055).
 *
 * <ul>
 * <li><b>Idempotence (POST)</b> : par (link_id, external_order_id) ; même corps ⇒ réponse existante ;
 * corps différent ⇒ {@code 409 IDEMPOTENCY_KEY_REUSED}.</li>
 * <li><b>Validation</b> : produit actif, prix approuvé, version et montant exacts, devise.</li>
 * <li><b>Réservation</b> : FOR UPDATE par (warehouse_id, product_id) pour éviter les deadlocks ;
 * stock dispo = sum(stock_level) − réservations actives ; TTL configurable (30 min par défaut).</li>
 * <li><b>Annulation</b> : CONVERTED → 409 ; RESERVED → réservation CANCELLED + commande CANCELLED ;
 * déjà CANCELLED/EXPIRED → idempotent.</li>
 * </ul>
 */
@Component
public class OnlineOrders {

	/** SQL — données de base d'un produit. */
	private static final String PRODUCT_CHECK = """
			select p.active, p.version
			from product p
			where p.id = :productId""";

	/** SQL — prix approuvé d'une liaison pour un produit. */
	private static final String APPROVED_PRICE = """
			select cp.price, cp.currency, cp.price_version
			from channel_price cp
			where cp.link_id = :link and cp.product_id = :product and cp.status = 'APPROVED'
			order by cp.price_version desc
			limit 1""";

	/** SQL — dépôts actifs de la liaison. */
	private static final String LINK_WAREHOUSES = """
			select lw.warehouse_id from integration_link_warehouse lw
			join warehouse w on w.id = lw.warehouse_id and w.active
			where lw.link_id = :link""";

	/**
	 * SQL — verrouillage déterministe des lignes de stock pour éviter les deadlocks.
	 * Verrou en ordre (warehouse_id, product_id).
	 */
	private static final String LOCK_STOCK = """
			select sl.warehouse_id, sl.product_id, sl.quantity
			from stock_level sl
			where sl.warehouse_id in (:warehouses) and sl.product_id in (:products)
			order by sl.warehouse_id, sl.product_id
			for update""";

	/** SQL — réservations actives non expirées sur ces produits et dépôts. */
	private static final String ACTIVE_RESERVATIONS = """
			select rl.warehouse_id, rl.product_id, sum(rl.quantity) as reserved
			from online_reservation_line rl
			join online_reservation r on r.id = rl.reservation_id
			where r.status = 'RESERVED' and r.expires_at > now()
			  and rl.warehouse_id in (:warehouses) and rl.product_id in (:products)
			group by rl.warehouse_id, rl.product_id""";

	/** SQL — insertion d'une commande. */
	private static final String INSERT_ORDER = """
			insert into online_order (id, write_id, link_id, external_order_id, request_hash, status, currency,
			  customer_name, customer_phone, delivery_mode, delivery_address, note, external_created_at)
			values (:id, :writeId, :link, :externalOrderId, :hash, :status, :currency,
			  :customerName, :customerPhone, :deliveryMode, :deliveryAddress, :note, :externalCreatedAt)""";

	/** SQL — insertion d'une ligne de commande. */
	private static final String INSERT_ORDER_LINE = """
			insert into online_order_line (id, order_id, product_id, quantity, unit_price, price_version, sku, label)
			values (:id, :orderId, :productId, :quantity, :unitPrice, :priceVersion, :sku, :label)""";

	/** SQL — insertion d'une réservation. */
	private static final String INSERT_RESERVATION = """
			insert into online_reservation (id, order_id, status, expires_at)
			values (:id, :orderId, 'RESERVED', :expiresAt)""";

	/** SQL — insertion d'une ligne de réservation. */
	private static final String INSERT_RESERVATION_LINE = """
			insert into online_reservation_line (id, reservation_id, warehouse_id, product_id, quantity)
			values (:id, :reservationId, :warehouseId, :productId, :quantity)""";

	/** SQL — mise à jour du statut d'une commande. */
	private static final String UPDATE_ORDER_STATUS = """
			update online_order set status = :status, updated_at = now()
			where id = :id""";

	/** SQL — mise à jour du statut et du motif d'annulation. */
	private static final String UPDATE_ORDER_CANCELLED = """
			update online_order set status = 'CANCELLED', cancel_reason = :reason, updated_at = now()
			where id = :id""";

	/** SQL — mise à jour du statut de la réservation. */
	private static final String UPDATE_RESERVATION_STATUS = """
			update online_reservation set status = :status, updated_at = now()
			where order_id = :orderId""";

	/** SQL — lecture d'une commande par id et liaison. */
	private static final String FIND_ORDER = """
			select o.id, o.write_id, o.link_id, o.external_order_id, o.status, o.currency,
			  o.customer_name, o.customer_phone, o.delivery_mode, o.delivery_address,
			  o.note, o.cancel_reason, o.external_created_at, o.created_at, o.request_hash
			from online_order o
			where o.id = :id and o.link_id = :link""";

	/** SQL — lecture d'une commande par external_order_id et liaison. */
	private static final String FIND_ORDER_BY_EXTERNAL = """
			select o.id, o.write_id, o.link_id, o.external_order_id, o.status, o.currency,
			  o.customer_name, o.customer_phone, o.delivery_mode, o.delivery_address,
			  o.note, o.cancel_reason, o.external_created_at, o.created_at, o.request_hash
			from online_order o
			where o.external_order_id = :externalOrderId and o.link_id = :link""";

	/** SQL — lignes d'une commande. */
	private static final String FIND_LINES = """
			select l.id, l.product_id, l.quantity, l.unit_price, l.price_version, l.sku, l.label
			from online_order_line l
			where l.order_id = :orderId
			order by l.id""";

	/** SQL — réservation d'une commande. */
	private static final String FIND_RESERVATION = """
			select r.id, r.order_id, r.status, r.expires_at
			from online_reservation r
			where r.order_id = :orderId""";

	/** SQL — lignes d'une réservation. */
	private static final String FIND_RESERVATION_LINES = """
			select rl.warehouse_id, rl.product_id, rl.quantity
			from online_reservation_line rl
			where rl.reservation_id = :reservationId
			order by rl.warehouse_id, rl.product_id""";

	/** SQL — vérification d'idempotence par external_order_id. */
	private static final String FIND_ORDER_ID_BY_EXTERNAL = """
			select o.id, o.request_hash
			from online_order o
			where o.external_order_id = :externalOrderId and o.link_id = :link""";

	private final JdbcClient jdbc;

	private final TransactionTemplate readWrite;

	private final TransactionTemplate readOnly;

	private final Duration reservationTtl;

	OnlineOrders(DataSource dataSource, PlatformTransactionManager transactionManager,
			IntegrationReservationProperties reservationProperties) {
		this.jdbc = JdbcClient.create(dataSource);
		this.readWrite = new TransactionTemplate(transactionManager);
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.reservationTtl = reservationProperties.ttl();
	}

	/**
	 * Crée une commande en ligne (idempotente par external_order_id).
	 *
	 * @param link     liaison
	 * @param currency devise de la liaison
	 * @param request  corps validé
	 * @return résultat avec flag created (true = 201, false = 200 idempotent)
	 * @throws ConflictException        IDEMPOTENCY_KEY_REUSED : même externalOrderId, corps différent
	 * @throws NotFoundException        PRODUCT_NOT_FOUND : produit inexistant
	 * @throws BusinessRuleException    PRODUCT_ARCHIVED, PRODUCT_NOT_LINKED, PRICE_VERSION_MISMATCH,
	 *                                  PRICE_NOT_APPROVED, PRICE_CURRENCY_MISMATCH
	 */
	public CreateResult create(LinkRef link, String currency, OnlineOrderRequest request) {
		String hash = request.hash();
		// Idempotence : vérification avant la transaction (lecture légère).
		Optional<OnlineOrderView> existing = findByExternalIdInternal(link, request.externalOrderId(), hash);
		if (existing.isPresent()) {
			return new CreateResult(false, existing.get());
		}
		try {
			OnlineOrderView view = this.readWrite.execute(status -> doCreate(link, currency, request, hash));
			return new CreateResult(true, view);
		}
		catch (DuplicateKeyException ex) {
			// Concurrence : deux créations simultanées → la seconde relit la première.
			return new CreateResult(false,
					findByExternalIdInternal(link, request.externalOrderId(), hash).orElseThrow(() -> ex));
		}
	}

	/** Résultat d'une tentative de création : flag de création et vue de la commande. */
	public record CreateResult(boolean created, OnlineOrderView view) {}

	/** Lecture d'une commande par son identifiant interne. */
	public Optional<OnlineOrderView> findById(LinkRef link, UUID orderId) {
		return this.readOnly.execute(status -> loadOrder(link, orderId));
	}

	/** Lecture d'une commande par son identifiant externe (liaison). */
	public Optional<OnlineOrderView> findByExternalId(LinkRef link, String externalOrderId) {
		return this.readOnly.execute(status -> loadOrderByExternal(link, externalOrderId));
	}

	/**
	 * Annulation d'une commande.
	 *
	 * @throws NotFoundException  ORDER_NOT_FOUND : commande inconnue ou d'une autre liaison
	 * @throws ConflictException  ORDER_NOT_CANCELLABLE : statut CONVERTED
	 */
	public OnlineOrderView cancel(LinkRef link, UUID orderId, String reason) {
		return this.readWrite.execute(status -> doCancel(link, orderId, reason));
	}

	// ---- Implémentation privée ----------------------------------------------------------------

	private Optional<OnlineOrderView> findByExternalIdInternal(LinkRef link, String externalOrderId, String expectedHash) {
		return this.jdbc.sql(FIND_ORDER_ID_BY_EXTERNAL)
			.param("externalOrderId", externalOrderId)
			.param("link", link.id())
			.query((rs, n) -> new String[] { rs.getString("id"), rs.getString("request_hash") })
			.optional()
			.map(row -> {
				if (!row[1].equals(expectedHash)) {
					throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED, null);
				}
				return loadOrder(link, UUID.fromString(row[0])).orElse(null);
			});
	}

	private OnlineOrderView doCreate(LinkRef link, String currency, OnlineOrderRequest request, String hash) {
		// 1. Validation des produits et des prix.
		List<ValidatedLine> validatedLines = new ArrayList<>();
		for (OnlineOrderRequest.OrderLine line : request.lines()) {
			// Produit actif ?
			boolean[] productState = { false };
			long[] productVersion = { 0L };
			boolean found = this.jdbc.sql(PRODUCT_CHECK)
				.param("productId", line.productId())
				.query((rs, n) -> {
					productState[0] = rs.getBoolean("active");
					productVersion[0] = rs.getLong("version");
					return Boolean.TRUE;
				})
				.optional()
				.isPresent();
			if (!found) {
				throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, null);
			}
			if (!productState[0]) {
				throw new BusinessRuleException(ErrorCode.PRODUCT_ARCHIVED, null);
			}
			// Prix approuvé pour la liaison ?
			record ApprovedPrice(BigDecimal price, String priceCurrency, long priceVersion) {}
			Optional<ApprovedPrice> approvedOpt = this.jdbc.sql(APPROVED_PRICE)
				.param("link", link.id())
				.param("product", line.productId())
				.query((rs, n) -> new ApprovedPrice(rs.getBigDecimal("price"), rs.getString("currency").trim(),
						rs.getLong("price_version")))
				.optional();
			if (approvedOpt.isEmpty()) {
				throw new BusinessRuleException(ErrorCode.PRODUCT_NOT_LINKED, null);
			}
			ApprovedPrice approved = approvedOpt.get();
			// Devise de la liaison.
			if (!currency.equals(approved.priceCurrency())) {
				throw new BusinessRuleException(ErrorCode.PRICE_CURRENCY_MISMATCH, null);
			}
			// Version du prix.
			if (line.priceVersion() != approved.priceVersion()) {
				throw new BusinessRuleException(ErrorCode.PRICE_VERSION_MISMATCH, null);
			}
			// Prix unitaire.
			BigDecimal requested = new BigDecimal(line.unitPrice()).setScale(2, RoundingMode.HALF_UP);
			BigDecimal approvedPrice = approved.price().setScale(2, RoundingMode.HALF_UP);
			if (requested.compareTo(approvedPrice) != 0) {
				throw new BusinessRuleException(ErrorCode.PRICE_NOT_APPROVED, null);
			}
			validatedLines.add(new ValidatedLine(line, approved.price()));
		}

		// 2. Insertion de la commande.
		UUID orderId = UUID.randomUUID();
		String initialStatus = "RECEIVED";
		this.jdbc.sql(INSERT_ORDER)
			.param("id", orderId)
			.param("writeId", UUID.randomUUID())
			.param("link", link.id())
			.param("externalOrderId", request.externalOrderId())
			.param("hash", hash)
			.param("status", initialStatus)
			.param("currency", currency)
			.param("customerName", request.customer().name())
			.param("customerPhone", request.customer().phone())
			.param("deliveryMode", request.delivery().mode())
			.param("deliveryAddress", request.delivery().address())
			.param("note", request.note())
			.param("externalCreatedAt", request.createdAt().atOffset(ZoneOffset.UTC))
			.update();

		for (ValidatedLine vl : validatedLines) {
			this.jdbc.sql(INSERT_ORDER_LINE)
				.param("id", UUID.randomUUID())
				.param("orderId", orderId)
				.param("productId", vl.line().productId())
				.param("quantity", vl.line().quantity())
				.param("unitPrice", vl.approvedPrice())
				.param("priceVersion", vl.line().priceVersion())
				.param("sku", vl.line().sku())
				.param("label", vl.line().label())
				.update();
		}

		// 3. Réservation de stock.
		List<UUID> warehouseIds = this.jdbc.sql(LINK_WAREHOUSES)
			.param("link", link.id())
			.query(UUID.class)
			.list();

		Set<UUID> productIds = new HashSet<>();
		for (ValidatedLine vl : validatedLines) {
			productIds.add(vl.line().productId());
		}

		String finalStatus;
		if (!warehouseIds.isEmpty() && !productIds.isEmpty()) {
			finalStatus = allocateStock(orderId, warehouseIds, productIds, validatedLines);
		}
		else {
			finalStatus = "REJECTED_STOCK";
		}

		this.jdbc.sql(UPDATE_ORDER_STATUS).param("status", finalStatus).param("id", orderId).update();

		return loadOrder(link, orderId).orElseThrow();
	}

	/**
	 * Alloue le stock et crée la réservation si possible.
	 * @return "RESERVED" ou "REJECTED_STOCK"
	 */
	private String allocateStock(UUID orderId, List<UUID> warehouseIds, Set<UUID> productIds,
			List<ValidatedLine> lines) {
		// Verrou déterministe : warehouse_id, product_id croissants.
		List<UUID> sortedWarehouses = new ArrayList<>(warehouseIds);
		sortedWarehouses.sort(Comparator.naturalOrder());
		List<UUID> sortedProducts = new ArrayList<>(productIds);
		sortedProducts.sort(Comparator.naturalOrder());

		// Stock actuel (avec verrou FOR UPDATE).
		Map<String, Integer> stock = new HashMap<>();
		this.jdbc.sql(LOCK_STOCK)
			.param("warehouses", sortedWarehouses)
			.param("products", sortedProducts)
			.query((rs, n) -> {
				String key = rs.getObject("warehouse_id", UUID.class) + ":" + rs.getObject("product_id", UUID.class);
				stock.put(key, rs.getInt("quantity"));
				return null;
			})
			.list();

		// Réservations actives.
		Map<String, Integer> reserved = new HashMap<>();
		this.jdbc.sql(ACTIVE_RESERVATIONS)
			.param("warehouses", sortedWarehouses)
			.param("products", sortedProducts)
			.query((rs, n) -> {
				String key = rs.getObject("warehouse_id", UUID.class) + ":" + rs.getObject("product_id", UUID.class);
				reserved.put(key, rs.getInt("reserved"));
				return null;
			})
			.list();

		// Disponibilité par produit (toutes entrepôts confondus).
		Map<UUID, Integer> available = new HashMap<>();
		for (UUID wId : sortedWarehouses) {
			for (UUID pId : sortedProducts) {
				String key = wId + ":" + pId;
				int qty = stock.getOrDefault(key, 0) - reserved.getOrDefault(key, 0);
				available.merge(pId, qty, Integer::sum);
			}
		}

		// Vérification.
		for (ValidatedLine vl : lines) {
			int avail = available.getOrDefault(vl.line().productId(), 0);
			if (avail < vl.line().quantity()) {
				return "REJECTED_STOCK";
			}
		}

		// Allocation : entrepôt avec le plus de stock d'abord.
		UUID reservationId = UUID.randomUUID();
		OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(reservationTtl);
		this.jdbc.sql(INSERT_RESERVATION)
			.param("id", reservationId)
			.param("orderId", orderId)
			.param("expiresAt", expiresAt)
			.update();

		for (ValidatedLine vl : lines) {
			int remaining = vl.line().quantity();
			// Trier les entrepôts par stock disponible décroissant pour ce produit.
			List<UUID> byStock = new ArrayList<>(sortedWarehouses);
			byStock.sort((a, b) -> {
				String ka = a + ":" + vl.line().productId();
				String kb = b + ":" + vl.line().productId();
				int sa = stock.getOrDefault(ka, 0) - reserved.getOrDefault(ka, 0);
				int sb = stock.getOrDefault(kb, 0) - reserved.getOrDefault(kb, 0);
				return Integer.compare(sb, sa);
			});
			for (UUID wId : byStock) {
				if (remaining <= 0) break;
				String key = wId + ":" + vl.line().productId();
				int avail = stock.getOrDefault(key, 0) - reserved.getOrDefault(key, 0);
				if (avail <= 0) continue;
				int alloc = Math.min(avail, remaining);
				this.jdbc.sql(INSERT_RESERVATION_LINE)
					.param("id", UUID.randomUUID())
					.param("reservationId", reservationId)
					.param("warehouseId", wId)
					.param("productId", vl.line().productId())
					.param("quantity", alloc)
					.update();
				// Update simulated state to avoid double allocation for next lines.
				reserved.merge(key, alloc, Integer::sum);
				remaining -= alloc;
			}
		}

		return "RESERVED";
	}

	private OnlineOrderView doCancel(LinkRef link, UUID orderId, String reason) {
		// Lire la commande (vérification liaison incluse).
		OrderRow row = this.jdbc.sql(FIND_ORDER)
			.param("id", orderId)
			.param("link", link.id())
			.query((rs, n) -> new OrderRow(
					rs.getObject("id", UUID.class),
					rs.getString("status"),
					rs.getString("cancel_reason"),
					rs.getString("external_order_id"),
					rs.getString("currency"),
					rs.getString("customer_name"),
					rs.getString("customer_phone"),
					rs.getString("delivery_mode"),
					rs.getString("delivery_address"),
					rs.getString("note"),
					rs.getObject("external_created_at", OffsetDateTime.class).toInstant(),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.optional()
			.orElseThrow(() -> new NotFoundException(ErrorCode.ORDER_NOT_FOUND, null));

		String current = row.status();

		// CONVERTED → 409.
		if ("CONVERTED".equals(current)) {
			throw new ConflictException(ErrorCode.ORDER_NOT_CANCELLABLE, null);
		}

		// Idempotence : déjà terminal.
		if ("CANCELLED".equals(current) || "EXPIRED".equals(current)) {
			return loadOrder(link, orderId).orElseThrow();
		}

		// Annuler la réservation si RESERVED.
		if ("RESERVED".equals(current)) {
			this.jdbc.sql(UPDATE_RESERVATION_STATUS)
				.param("status", "CANCELLED")
				.param("orderId", orderId)
				.update();
		}

		this.jdbc.sql(UPDATE_ORDER_CANCELLED)
			.param("id", orderId)
			.param("reason", reason != null ? MaskedValue.of(reason) : null)
			.update();

		return loadOrder(link, orderId).orElseThrow();
	}

	private Optional<OnlineOrderView> loadOrder(LinkRef link, UUID orderId) {
		Optional<OrderRow> orderOpt = this.jdbc.sql(FIND_ORDER)
			.param("id", orderId)
			.param("link", link.id())
			.query((rs, n) -> new OrderRow(
					rs.getObject("id", UUID.class),
					rs.getString("status"),
					rs.getString("cancel_reason"),
					rs.getString("external_order_id"),
					rs.getString("currency"),
					rs.getString("customer_name"),
					rs.getString("customer_phone"),
					rs.getString("delivery_mode"),
					rs.getString("delivery_address"),
					rs.getString("note"),
					rs.getObject("external_created_at", OffsetDateTime.class).toInstant(),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.optional();
		if (orderOpt.isEmpty()) {
			return Optional.empty();
		}
		OrderRow order = orderOpt.get();

		List<OrderLineRow> lines = this.jdbc.sql(FIND_LINES)
			.param("orderId", orderId)
			.query((rs, n) -> new OrderLineRow(
					rs.getObject("id", UUID.class),
					rs.getObject("product_id", UUID.class),
					rs.getInt("quantity"),
					rs.getBigDecimal("unit_price"),
					rs.getLong("price_version"),
					rs.getString("sku"),
					rs.getString("label")))
			.list();

		Optional<ReservationRow> reservationOpt = this.jdbc.sql(FIND_RESERVATION)
			.param("orderId", orderId)
			.query((rs, n) -> new ReservationRow(
					rs.getObject("id", UUID.class),
					rs.getString("status"),
					rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
			.optional();

		List<ReservationLineRow> reservationLines = reservationOpt.map(r -> this.jdbc.sql(FIND_RESERVATION_LINES)
			.param("reservationId", r.id())
			.query((rs, n) -> new ReservationLineRow(
					rs.getObject("warehouse_id", UUID.class),
					rs.getObject("product_id", UUID.class),
					rs.getInt("quantity")))
			.list()).orElse(List.of());

		return Optional.of(new OnlineOrderView(order, lines, reservationOpt.orElse(null), reservationLines));
	}

	private Optional<OnlineOrderView> loadOrderByExternal(LinkRef link, String externalOrderId) {
		Optional<UUID> orderIdOpt = this.jdbc.sql(FIND_ORDER_BY_EXTERNAL)
			.param("externalOrderId", externalOrderId)
			.param("link", link.id())
			.query((rs, n) -> rs.getObject("id", UUID.class))
			.optional();
		return orderIdOpt.flatMap(id -> loadOrder(link, id));
	}

	// ---- Records de données -----------------------------------------------------------------

	private record ValidatedLine(OnlineOrderRequest.OrderLine line, BigDecimal approvedPrice) {}

	private record OrderRow(UUID id, String status, String cancelReason, String externalOrderId,
			String currency, String customerName, String customerPhone, String deliveryMode,
			String deliveryAddress, String note, Instant externalCreatedAt, Instant createdAt) {}

	private record OrderLineRow(UUID id, UUID productId, int quantity, BigDecimal unitPrice,
			long priceVersion, String sku, String label) {}

	private record ReservationRow(UUID id, String status, Instant expiresAt) {}

	private record ReservationLineRow(UUID warehouseId, UUID productId, int quantity) {}

	// ---- Vue publique -----------------------------------------------------------------------

	/**
	 * Vue complète d'une commande en ligne.
	 */
	public record OnlineOrderView(OrderRow order, List<OrderLineRow> lines,
			ReservationRow reservation, List<ReservationLineRow> reservationLines) {

		public UUID orderId() { return order().id(); }
		public String externalOrderId() { return order().externalOrderId(); }
		public String status() { return order().status(); }
		public String cancelReason() { return order().cancelReason(); }
		public String currency() { return order().currency(); }
		public String customerName() { return order().customerName(); }
		public String customerPhone() { return order().customerPhone(); }
		public String deliveryMode() { return order().deliveryMode(); }
		public String deliveryAddress() { return order().deliveryAddress(); }
		public String note() { return order().note(); }
		public Instant externalCreatedAt() { return order().externalCreatedAt(); }
		public Instant createdAt() { return order().createdAt(); }

		/** Lignes de la commande. */
		public List<OrderLineView> lineViews() {
			return lines().stream()
				.map(l -> new OrderLineView(l.productId(), l.quantity(),
						l.unitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString(),
						l.priceVersion(), l.sku(), l.label()))
				.toList();
		}

		/** Vue de la réservation, ou {@code null} si absente. */
		public ReservationView reservationView() {
			if (reservation() == null) return null;
			List<AllocationView> allocations = reservationLines().stream()
				.map(rl -> new AllocationView(rl.warehouseId(), rl.productId(), rl.quantity()))
				.toList();
			return new ReservationView(reservation().id(), reservation().status(), reservation().expiresAt(), allocations);
		}

	}

	/** Vue d'une ligne de commande. */
	public record OrderLineView(UUID productId, int quantity, String unitPrice, long priceVersion,
			String sku, String label) {}

	/** Vue de la réservation. */
	public record ReservationView(UUID id, String status, Instant expiresAt, List<AllocationView> allocations) {}

	/** Allocation d'une réservation : entrepôt, produit, quantité allouée. */
	public record AllocationView(UUID warehouseId, UUID productId, int quantity) {}

	// ---- Corps de la requête ----------------------------------------------------------------

	/**
	 * Corps de {@code POST /online-orders}, déjà validé par la route.
	 */
	public record OnlineOrderRequest(String externalOrderId, Instant createdAt, String currency,
			Customer customer, Delivery delivery, String note, List<OrderLine> lines) {

		/** Empreinte SHA-256 canonique du corps (idempotence). */
		public String hash() {
			StringBuilder sb = new StringBuilder();
			sb.append(externalOrderId()).append('\n');
			sb.append(createdAt()).append('\n');
			sb.append(currency()).append('\n');
			sb.append(customer().name()).append('\n');
			sb.append(customer().phone() != null ? "1" + customer().phone() : "0").append('\n');
			sb.append(delivery().mode()).append('\n');
			sb.append(delivery().address() != null ? "1" + delivery().address() : "0").append('\n');
			sb.append(note() != null ? "1" + note() : "0").append('\n');
			for (OrderLine line : lines()) {
				sb.append(line.productId()).append(':')
					.append(line.quantity()).append(':')
					.append(new BigDecimal(line.unitPrice()).setScale(2, RoundingMode.HALF_UP).toPlainString()).append(':')
					.append(line.priceVersion()).append(':')
					.append(line.sku() != null ? "1" + line.sku() : "0").append(':')
					.append(line.label() != null ? "1" + line.label() : "0").append('\n');
			}
			try {
				return java.util.HexFormat.of().formatHex(
						MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
			}
			catch (NoSuchAlgorithmException ex) {
				throw new IllegalStateException(ex);
			}
		}

		@Override
		public String toString() {
			return "OnlineOrderRequest[externalOrderId=" + externalOrderId() + "]";
		}

		/** Données client de la commande. */
		public record Customer(String name, String phone) {}

		/** Livraison : mode et adresse optionnelle. */
		public record Delivery(String mode, String address) {}

		/** Ligne de commande. */
		public record OrderLine(UUID productId, int quantity, String unitPrice, long priceVersion,
				String sku, String label) {}

	}

}
