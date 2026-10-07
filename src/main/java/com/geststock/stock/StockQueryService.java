package com.geststock.stock;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.product.ProductSnapshot;
import com.geststock.product.ProductService;
import com.geststock.product.ProductStockReader;
import com.geststock.stock.dto.StockLevelResponse;
import com.geststock.stock.dto.StockMovementFilter;
import com.geststock.stock.dto.StockMovementResponse;
import com.geststock.user.UserAccountService;

/**
 * Lectures du stock : quantités (affichées par le catalogue produits), niveaux et historique
 * des mouvements. Aucune écriture ici.
 */
@Service
@Transactional(readOnly = true)
public class StockQueryService implements ProductStockReader {

	static final SortWhitelist MOVEMENT_SORTS = new SortWhitelist(
			Map.of("createdAt", "createdAt", "quantity", "quantity", "type", "type"),
			Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

	static final SortWhitelist LEVEL_SORTS = new SortWhitelist(
			Map.of("quantity", "quantity", "updatedAt", "updatedAt"),
			Sort.by(Sort.Order.desc("updatedAt")), List.of("warehouseId", "productId"));

	private final StockLevelRepository levels;

	private final StockMovementRepository movements;

	private final ProductService products;

	private final UserAccountService users;

	private final StockReadQueries search;

	StockQueryService(StockLevelRepository levels, StockMovementRepository movements, ProductService products,
			UserAccountService users, StockReadQueries search) {
		this.search = search;
		this.levels = levels;
		this.movements = movements;
		this.products = products;
		this.users = users;
	}

	/** Quantités disponibles dans un dépôt ; {@code null} → aucune (affichées à 0). */
	@Override
	public Map<UUID, Integer> quantities(Collection<UUID> productIds, UUID warehouseId) {
		if (warehouseId == null || productIds.isEmpty()) {
			return Map.of();
		}
		return this.levels.findAllByWarehouse(warehouseId, productIds).stream()
			.collect(Collectors.toUnmodifiableMap(StockLevel::getProductId, StockLevel::getQuantity));
	}

	/**
	 * Historique des mouvements.
	 *
	 * @param warehouseId dépôt, ou {@code null} pour tous les dépôts
	 */
	public Page<StockMovementResponse> searchMovements(StockMovementFilter filter, UUID warehouseId, PageQuery query) {
		Specification<StockMovement> spec = Specification.unrestricted();
		if (warehouseId != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
		}
		if (filter.productId() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("productId"), filter.productId()));
		}
		if (filter.from() != null) {
			spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
		}
		if (filter.to() != null) {
			spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), filter.to()));
		}
		if (filter.type() != null && !filter.type().isEmpty()) {
			spec = spec.and((root, q, cb) -> root.get("type").in(filter.type()));
		}
		Page<StockMovement> page = this.search.findAll(StockMovement.class, spec, query.toPageable(MOVEMENT_SORTS));
		List<StockMovementResponse> responses = toResponses(page.getContent());
		Map<UUID, StockMovementResponse> byId = responses.stream()
			.collect(Collectors.toMap(StockMovementResponse::id, r -> r));
		return page.map(movement -> byId.get(movement.getId()));
	}

	public StockMovement getMovement(UUID id) {
		return this.movements.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.STOCK_MOVEMENT_NOT_FOUND,
					ErrorCode.STOCK_MOVEMENT_NOT_FOUND.defaultMessage()));
	}

	/** Niveaux d'un dépôt (lignes à zéro comprises), éventuellement pour un seul produit. */
	public Page<StockLevelResponse> searchLevels(UUID warehouseId, UUID productId, PageQuery query) {
		Specification<StockLevel> spec = (root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId);
		if (productId != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("productId"), productId));
		}
		Page<StockLevel> page = this.search.findAll(StockLevel.class, spec, query.toPageable(LEVEL_SORTS));
		Map<UUID, ProductSnapshot> productById = this.products
			.snapshots(page.getContent().stream().map(StockLevel::getProductId).collect(Collectors.toSet()));
		return page.map(level -> {
			ProductSnapshot product = productById.get(level.getProductId());
			return new StockLevelResponse(level.getWarehouseId(), level.getProductId(),
					product != null ? product.sku() : null, product != null ? product.name() : null, level.getQuantity(),
					level.getLastMovementId(), level.getUpdatedAt());
		});
	}

	/** Réponses enrichies (référence et nom du produit, identifiant de l'auteur), en lot. */
	public List<StockMovementResponse> toResponses(List<StockMovement> list) {
		Set<UUID> productIds = new HashSet<>();
		Set<UUID> userIds = new HashSet<>();
		list.forEach(movement -> {
			productIds.add(movement.getProductId());
			userIds.add(movement.getCreatedBy());
		});
		Map<UUID, ProductSnapshot> productById = this.products.snapshots(productIds);
		Map<UUID, String> usernames = this.users.usernames(userIds);
		return list.stream().map(movement -> {
			ProductSnapshot product = productById.get(movement.getProductId());
			return new StockMovementResponse(movement.getId(), movement.getWarehouseId(), movement.getProductId(),
					product != null ? product.sku() : null, product != null ? product.name() : null,
					movement.getQuantity(), movement.getType(), movement.getType().legacyReason(), movement.getReason(),
					movement.getUnitCost(), movement.getBalanceAfter(), movement.getSourceType(), movement.getSourceId(),
					movement.getSourceReference(), movement.getCorrelationId(), movement.getReversalOfId(),
					movement.getCreatedAt(), movement.getCreatedBy(), usernames.get(movement.getCreatedBy()));
		}).toList();
	}

	public StockMovementResponse toResponse(StockMovement movement) {
		return toResponses(List.of(movement)).get(0);
	}

}
