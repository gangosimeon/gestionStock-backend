package com.geststock.stock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Subquery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SearchText;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.product.ProductService;
import com.geststock.product.ProductSnapshot;
import com.geststock.stock.dto.StockTransferFilter;
import com.geststock.stock.dto.StockTransferResponse;
import com.geststock.stock.dto.TransferStockRequest;
import com.geststock.user.UserAccountService;
import com.geststock.warehouse.Warehouse;
import com.geststock.warehouse.WarehouseAccess;
import com.geststock.warehouse.WarehouseService;

/**
 * Transferts entre dépôts : pièce numérotée (reçu) d'un ou plusieurs produits, historique des
 * transferts envoyés et reçus, retour au dépôt d'origine. Les quantités ne bougent que par
 * {@link StockService#moveBetween} : cette pièce n'en porte aucune.
 *
 * <p>Un transfert est visible depuis ses deux dépôts. Les permissions et le dépôt courant
 * sont contrôlés par le contrôleur ; l'accès aux deux dépôts est vérifié ici pour le retour.
 */
@Service
@Transactional(readOnly = true)
public class StockTransferService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of(
			"createdAt", "createdAt",
			"number", "number"),
			Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("number")));

	private final StockTransferRepository transfers;

	private final StockService stock;

	private final ProductService products;

	private final WarehouseService warehouses;

	private final WarehouseAccess warehouseAccess;

	private final UserAccountService users;

	private final AuditService audit;

	private final Clock clock;

	private final ZoneId zone;

	StockTransferService(StockTransferRepository transfers, StockService stock, ProductService products,
			WarehouseService warehouses, WarehouseAccess warehouseAccess, UserAccountService users, AuditService audit,
			Clock clock, @Value("${geststock.dashboard.time-zone:Africa/Abidjan}") String zone) {
		this.transfers = transfers;
		this.stock = stock;
		this.products = products;
		this.warehouses = warehouses;
		this.warehouseAccess = warehouseAccess;
		this.users = users;
		this.audit = audit;
		this.clock = clock;
		this.zone = ZoneId.of(zone);
	}

	// --- Lecture ------------------------------------------------------------------------------------

	/**
	 * Transferts envoyés par ou reçus dans {@code warehouseId}, ou de tous les dépôts si
	 * {@code null} (l'appelant a vérifié {@code warehouse:all}).
	 */
	public Page<StockTransferResponse> search(StockTransferFilter filter, UUID warehouseId, PageQuery query) {
		Specification<StockTransfer> spec = Specification.unrestricted();
		if (warehouseId != null) {
			StockTransferFilter.Direction direction = filter.direction();
			spec = spec.and((root, q, cb) -> {
				if (direction == StockTransferFilter.Direction.SENT) {
					return cb.equal(root.get("fromWarehouseId"), warehouseId);
				}
				if (direction == StockTransferFilter.Direction.RECEIVED) {
					return cb.equal(root.get("toWarehouseId"), warehouseId);
				}
				return cb.or(cb.equal(root.get("fromWarehouseId"), warehouseId),
						cb.equal(root.get("toWarehouseId"), warehouseId));
			});
		}
		if (filter.status() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), filter.status()));
		}
		if (filter.productId() != null) {
			spec = spec.and((root, q, cb) -> {
				Subquery<UUID> lines = q.subquery(UUID.class);
				var line = lines.from(StockTransferLine.class);
				return cb.exists(lines.select(line.get("id"))
					.where(cb.equal(line.get("transfer"), root), cb.equal(line.get("productId"), filter.productId())));
			});
		}
		if (filter.from() != null) {
			Instant start = startOf(filter.from());
			spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), start));
		}
		if (filter.to() != null) {
			Instant end = startOf(filter.to().plusDays(1));
			spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), end));
		}
		if (SearchText.present(filter.q())) {
			String pattern = SearchText.pattern(filter.q());
			spec = spec.and((root, q, cb) -> {
				Subquery<UUID> lines = q.subquery(UUID.class);
				var line = lines.from(StockTransferLine.class);
				lines.select(line.get("id"))
					.where(cb.equal(line.get("transfer"), root),
							cb.or(cb.like(cb.lower(line.get("productSku")), pattern, '\\'),
									cb.like(cb.lower(line.get("productName")), pattern, '\\')));
				return cb.or(cb.like(cb.lower(root.get("number")), pattern, '\\'),
						cb.like(cb.lower(cb.coalesce(root.get("note"), "")), pattern, '\\'), cb.exists(lines));
			});
		}
		Page<StockTransfer> page = this.transfers.findAll(spec, query.toPageable(SORTS));
		Map<UUID, String> usernames = usernames(page.getContent());
		return page.map((transfer) -> toResponse(transfer, usernames));
	}

	/** Transfert visible depuis l'un de ses deux dépôts ; sinon introuvable (404). */
	public StockTransferResponse get(UUID id) {
		StockTransfer transfer = this.transfers.findById(id).orElseThrow(StockTransferService::notFound);
		requireVisible(transfer);
		return toResponse(transfer);
	}

	// --- Écriture -----------------------------------------------------------------------------------

	/**
	 * Transfert des produits {@code items} de {@code fromWarehouseId} vers {@code toWarehouseId} :
	 * pièce numérotée et ses mouvements, atomiques (un produit insuffisant : rien n'est
	 * enregistré). Un produit figure au plus une fois.
	 */
	@Transactional
	public Created create(UUID fromWarehouseId, UUID toWarehouseId, List<TransferStockRequest.Item> items,
			String note) {
		if (fromWarehouseId.equals(toWarehouseId)) {
			throw new InvalidRequestException(ErrorCode.SAME_WAREHOUSE_TRANSFER,
					ErrorCode.SAME_WAREHOUSE_TRANSFER.defaultMessage());
		}
		Map<UUID, Integer> quantities = new LinkedHashMap<>();
		for (TransferStockRequest.Item item : items) {
			if (quantities.put(item.productId(), item.quantity()) != null) {
				throw new InvalidRequestException(ErrorCode.STOCK_TRANSFER_DUPLICATE_PRODUCT,
						ErrorCode.STOCK_TRANSFER_DUPLICATE_PRODUCT.defaultMessage());
			}
		}
		if (quantities.isEmpty()) {
			throw new InvalidRequestException(ErrorCode.INVALID_STOCK_QUANTITY,
					ErrorCode.INVALID_STOCK_QUANTITY.defaultMessage());
		}
		Warehouse from = this.warehouses.getActive(fromWarehouseId);
		Warehouse to = this.warehouses.getActive(toWarehouseId);
		Map<UUID, ProductSnapshot> productById = this.products.snapshots(quantities.keySet());
		if (productById.size() != quantities.size()) {
			throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND.defaultMessage());
		}
		String text = StringUtils.hasText(note) ? note.trim() : null;
		StockTransfer transfer = new StockTransfer(nextNumber(), from.getId(), from.getName(), to.getId(), to.getName(),
				text);
		quantities.forEach((productId, quantity) -> {
			ProductSnapshot product = productById.get(productId);
			transfer.addLine(productId, product.sku(), product.name(), quantity);
		});
		this.transfers.saveAndFlush(transfer);

		List<StockMovement> movements = this.stock.moveBetween(fromWarehouseId, toWarehouseId, quantities,
				text != null ? text : "Transfert " + transfer.getNumber(), transfer.getId(), transfer.getId(),
				transfer.getNumber());
		this.audit.record(AuditEvent.of("TRANSFER", "WAREHOUSE_TRANSFER", transfer.getId(), transfer.getNumber())
			.details("Transfert " + transfer.getNumber() + " de " + from.getName() + " vers " + to.getName() + " : "
					+ describe(transfer) + (text != null ? " — " + text : ""))
			.before(balances(movements, true))
			.after(balances(movements, false))
			.meta("fromWarehouseId", fromWarehouseId)
			.meta("toWarehouseId", toWarehouseId)
			.meta("productIds", List.copyOf(quantities.keySet()))
			.warehouse(fromWarehouseId));
		return new Created(toResponse(transfer), movements);
	}

	/**
	 * Retour du transfert : les quantités transférées sortent du dépôt de destination et
	 * reviennent au dépôt d'origine, toutes les lignes et une seule fois. Refusé si la
	 * destination n'a plus l'un des produits ({@code STOCK_INSUFFICIENT}) ou si l'utilisateur n'a
	 * pas accès aux deux dépôts.
	 *
	 * @param currentWarehouseId dépôt courant : l'origine ou la destination du transfert
	 */
	@Transactional
	public StockTransferResponse returnToOrigin(UUID id, String reason, UUID currentWarehouseId) {
		StockTransfer transfer = this.transfers.findForUpdate(id).orElseThrow(StockTransferService::notFound);
		requireVisible(transfer);
		if (!transfer.getFromWarehouseId().equals(currentWarehouseId)
				&& !transfer.getToWarehouseId().equals(currentWarehouseId)) {
			throw new ConflictException(ErrorCode.WAREHOUSE_MISMATCH, ErrorCode.WAREHOUSE_MISMATCH.defaultMessage());
		}
		if (transfer.getStatus() == StockTransferStatus.RETURNED) {
			throw new ConflictException(ErrorCode.STOCK_TRANSFER_ALREADY_RETURNED,
					ErrorCode.STOCK_TRANSFER_ALREADY_RETURNED.defaultMessage());
		}
		this.warehouseAccess.requireAccessible(transfer.getFromWarehouseId());
		this.warehouseAccess.requireAccessible(transfer.getToWarehouseId());
		UUID actor = ActorIdentity.currentUserId().orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));

		String text = StringUtils.hasText(reason) ? reason.trim() : null;
		String note = "Retour du transfert " + transfer.getNumber() + (text != null ? " — " + text : "");
		Map<UUID, Integer> quantities = new LinkedHashMap<>();
		transfer.getLines().forEach((line) -> quantities.put(line.getProductId(), line.getQuantity()));
		List<StockMovement> movements = this.stock.moveBetween(transfer.getToWarehouseId(),
				transfer.getFromWarehouseId(), quantities, note, transfer.getId(), UUID.randomUUID(),
				transfer.getNumber());
		transfer.markReturned(this.clock.instant(), actor, text);
		this.transfers.flush();

		this.audit.record(AuditEvent.of("RETURN", "WAREHOUSE_TRANSFER", transfer.getId(), transfer.getNumber())
			.details("Retour du transfert " + transfer.getNumber() + " de " + transfer.getToWarehouseName() + " vers "
					+ transfer.getFromWarehouseName() + " : " + describe(transfer) + (text != null ? " — " + text : ""))
			.before(balances(movements, true))
			.after(balances(movements, false))
			.meta("fromWarehouseId", transfer.getFromWarehouseId())
			.meta("toWarehouseId", transfer.getToWarehouseId())
			.meta("productIds", List.copyOf(quantities.keySet()))
			.warehouse(currentWarehouseId));
		return toResponse(transfer);
	}

	/** Transfert enregistré et ses mouvements (par ligne : sortie, entrée). */
	public record Created(StockTransferResponse transfer, List<StockMovement> movements) {
	}

	// --- Interne ------------------------------------------------------------------------------------

	private void requireVisible(StockTransfer transfer) {
		if (!this.warehouseAccess.canSee(transfer.getFromWarehouseId())
				&& !this.warehouseAccess.canSee(transfer.getToWarehouseId())) {
			throw notFound();
		}
	}

	private String nextNumber() {
		return "TRF-" + LocalDate.now(this.clock.withZone(this.zone)).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.transfers.nextNumber());
	}

	private Instant startOf(LocalDate day) {
		return day.atStartOfDay(this.zone).toInstant();
	}

	/** « 4 × SKU · Nom, 2 × … » pour le journal d'audit. */
	private static String describe(StockTransfer transfer) {
		return transfer.getLines()
			.stream()
			.map((line) -> line.getQuantity() + " × " + line.getProductSku() + " · " + line.getProductName())
			.collect(Collectors.joining(", "));
	}

	/**
	 * Stock de chaque produit par dépôt, avant ({@code before}) ou après les mouvements :
	 * {@code {"SKU@dépôt": quantité}}.
	 */
	private Map<String, Integer> balances(List<StockMovement> movements, boolean before) {
		Map<UUID, String> skuById = this.products
			.snapshots(movements.stream().map(StockMovement::getProductId).collect(Collectors.toSet()))
			.entrySet()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, (e) -> e.getValue().sku()));
		Map<String, Integer> balances = new LinkedHashMap<>();
		for (StockMovement movement : movements) {
			int balance = before ? movement.getBalanceAfter() - movement.getQuantity() : movement.getBalanceAfter();
			balances.put(skuById.get(movement.getProductId()) + "@" + movement.getWarehouseId(), balance);
		}
		return balances;
	}

	private Map<UUID, String> usernames(List<StockTransfer> list) {
		Set<UUID> ids = new HashSet<>();
		list.forEach((transfer) -> {
			if (transfer.getCreatedBy() != null) {
				ids.add(transfer.getCreatedBy());
			}
			if (transfer.getReturnedBy() != null) {
				ids.add(transfer.getReturnedBy());
			}
		});
		return ids.isEmpty() ? Map.of() : this.users.usernames(ids);
	}

	private StockTransferResponse toResponse(StockTransfer transfer) {
		return toResponse(transfer, usernames(List.of(transfer)));
	}

	private static StockTransferResponse toResponse(StockTransfer transfer, Map<UUID, String> usernames) {
		List<StockTransferResponse.Line> lines = transfer.getLines()
			.stream()
			.map((line) -> new StockTransferResponse.Line(line.getLineNumber(), line.getProductId(),
					line.getProductSku(), line.getProductName(), line.getQuantity()))
			.toList();
		return new StockTransferResponse(transfer.getId(), transfer.getNumber(), transfer.getFromWarehouseId(),
				transfer.getFromWarehouseName(), transfer.getToWarehouseId(), transfer.getToWarehouseName(), lines,
				transfer.getTotalQuantity(), transfer.getNote(), transfer.getStatus(), transfer.getCreatedAt(),
				transfer.getCreatedBy(), username(usernames, transfer.getCreatedBy()), transfer.getReturnedAt(),
				transfer.getReturnedBy(), username(usernames, transfer.getReturnedBy()), transfer.getReturnReason());
	}

	private static String username(Map<UUID, String> usernames, UUID id) {
		return id != null ? usernames.get(id) : null;
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.STOCK_TRANSFER_NOT_FOUND,
				ErrorCode.STOCK_TRANSFER_NOT_FOUND.defaultMessage());
	}

}
