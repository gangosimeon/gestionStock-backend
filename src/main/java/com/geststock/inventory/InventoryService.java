package com.geststock.inventory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditService;
import com.geststock.category.CategoryService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.common.security.ActorPermissions;
import com.geststock.common.security.Permissions;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.inventory.dto.CreateInventoryRequest;
import com.geststock.inventory.dto.InventoryCountsRequest;
import com.geststock.inventory.dto.InventoryFilter;
import com.geststock.inventory.dto.InventoryResponse;
import com.geststock.inventory.dto.LegacyInventoryRequest;
import com.geststock.product.ProductService;
import com.geststock.product.ProductSnapshot;
import com.geststock.product.ProductStockReader;
import com.geststock.stock.StockMovement;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Inventaires (API publique du module {@code inventory}).
 *
 * <p>Workflow : brouillon (périmètre) → démarrage → comptage (quantité théorique relevée par
 * le serveur au moment de chaque comptage) → soumission (tout compté) → <b>validation</b>
 * ({@code inventory:validate}) → terminé. Au plus un inventaire ouvert par dépôt.
 *
 * <p>La validation s'exécute dans <b>une transaction</b> : inventaire verrouillé, écarts non
 * nuls appliqués au stock en un seul appel atomique à {@link StockService} (mouvements
 * {@code INVENTORY}, source l'inventaire), lignes liées à leur mouvement, inventaire terminé,
 * trace d'audit. Au moindre refus (par exemple un écart négatif sur un stock déjà vendu
 * depuis le comptage), rien n'est appliqué. Un inventaire terminé est figé (base comprise).
 */
@Service
@Transactional(readOnly = true)
public class InventoryService {

	static final SortWhitelist SORTS = new SortWhitelist(Map.of(
			"createdAt", "createdAt",
			"number", "number",
			"validatedAt", "validatedAt"), Sort.by(Sort.Direction.DESC, "createdAt"));

	private final InventorySessionRepository inventories;

	private final ProductService products;

	private final CategoryService categories;

	private final ProductStockReader stockReader;

	private final StockService stock;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final EntityManager entityManager;

	private final Clock clock;

	InventoryService(InventorySessionRepository inventories, ProductService products, CategoryService categories,
			ProductStockReader stockReader, StockService stock, WarehouseAccess warehouseAccess, AuditService audit,
			EntityManager entityManager, Clock clock) {
		this.inventories = inventories;
		this.products = products;
		this.categories = categories;
		this.stockReader = stockReader;
		this.stock = stock;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	// --- Consultation --------------------------------------------------------------------------

	/** Inventaires d'un dépôt, ou de tous si {@code null} (l'appelant a vérifié warehouse:all). */
	public Page<InventoryResponse> search(InventoryFilter filter, UUID warehouseId, PageQuery query) {
		Specification<InventorySession> spec = Specification.unrestricted();
		if (warehouseId != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("warehouseId"), warehouseId));
		}
		if (filter.status() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), filter.status()));
		}
		if (com.geststock.common.api.SearchText.present(filter.q())) {
			String pattern = com.geststock.common.api.SearchText.pattern(filter.q());
			spec = spec.and((root, q, cb) -> cb.like(cb.lower(root.get("number")), pattern, '\\'));
		}
		if (filter.from() != null) {
			spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
		}
		if (filter.to() != null) {
			spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), filter.to()));
		}
		return this.inventories.findAll(spec, query.toPageable(SORTS)).map(InventoryService::toResponse);
	}

	public InventoryResponse get(UUID id) {
		InventorySession inventory = this.inventories.findById(id).orElseThrow(InventoryService::notFound);
		requireVisible(inventory);
		return toResponse(inventory);
	}

	// --- Workflow ------------------------------------------------------------------------------

	/** Crée un inventaire en brouillon, avec les produits de son périmètre. */
	@Transactional
	public InventoryResponse create(CreateInventoryRequest request, UUID currentWarehouseId) {
		UUID actor = actor();
		// Inventaire du dépôt courant ; un warehouseId envoyé doit le confirmer.
		UUID warehouseId = this.warehouseAccess.requireCurrentIfPresent(request.warehouseId(), currentWarehouseId);
		if (this.inventories.existsOpenInWarehouse(warehouseId)) {
			throw new ConflictException(ErrorCode.INVENTORY_ALREADY_OPEN, ErrorCode.INVENTORY_ALREADY_OPEN.defaultMessage());
		}
		List<ProductSnapshot> scope = switch (request.scope()) {
			case FULL -> this.products.activeProducts(null);
			case CATEGORY -> {
				this.categories.getForAssignment(request.categoryId(), false);
				yield this.products.activeProducts(request.categoryId());
			}
			case SELECTION -> selection(request.productIds());
		};
		if (scope.isEmpty()) {
			throw new BusinessRuleException(ErrorCode.INVENTORY_EMPTY, ErrorCode.INVENTORY_EMPTY.defaultMessage());
		}

		InventorySession inventory = new InventorySession(nextNumber(), warehouseId, request.scope(),
				request.scope() == InventoryScope.CATEGORY ? request.categoryId() : null, blankToNull(request.note()));
		scope.forEach(product -> inventory.addProduct(product.id(), product.sku(), product.name()));
		this.inventories.saveAndFlush(inventory);
		return traced("CREATE", inventory, "Inventaire " + inventory.getNumber() + " créé (" + request.scope() + ", "
				+ inventory.getLines().size() + " produit(s))", actor);
	}

	@Transactional
	public InventoryResponse addProducts(UUID id, List<UUID> productIds, UUID currentWarehouseId) {
		InventorySession inventory = lock(id, currentWarehouseId);
		List<ProductSnapshot> added = selection(productIds);
		added.forEach(product -> inventory.addProduct(product.id(), product.sku(), product.name()));
		this.inventories.saveAndFlush(inventory);
		return traced("ADD_PRODUCTS", inventory, "Inventaire " + inventory.getNumber() + " : ajout de "
				+ added.stream().map(ProductSnapshot::sku).collect(java.util.stream.Collectors.joining(", ")), null);
	}

	@Transactional
	public InventoryResponse removeProduct(UUID id, UUID productId, UUID currentWarehouseId) {
		InventorySession inventory = lock(id, currentWarehouseId);
		InventoryLine removed = inventory.line(productId).orElseThrow(InventoryService::notInScope);
		inventory.removeProduct(productId);
		this.inventories.saveAndFlush(inventory);
		return traced("REMOVE_PRODUCT", inventory, "Inventaire " + inventory.getNumber() + " : retrait de "
				+ removed.getProductSku(), null);
	}

	/** Démarre : le périmètre est figé, le comptage peut commencer. */
	@Transactional
	public InventoryResponse start(UUID id, UUID currentWarehouseId) {
		UUID actor = actor();
		InventorySession inventory = lock(id, currentWarehouseId);
		if (inventory.getLines().isEmpty()) {
			throw new BusinessRuleException(ErrorCode.INVENTORY_EMPTY, ErrorCode.INVENTORY_EMPTY.defaultMessage());
		}
		inventory.start(Instant.now(this.clock), actor);
		this.inventories.saveAndFlush(inventory);
		return traced("START", inventory, "Inventaire " + inventory.getNumber() + " démarré", actor);
	}

	/**
	 * Enregistre des comptages. La quantité théorique de chaque produit est relevée dans le
	 * stock du dépôt au même instant ; l'écart en découle.
	 */
	@Transactional
	public InventoryResponse recordCounts(UUID id, List<InventoryCountsRequest.Count> counts, UUID currentWarehouseId) {
		UUID actor = actor();
		InventorySession inventory = lock(id, currentWarehouseId);
		Set<UUID> productIds = new LinkedHashSet<>();
		for (InventoryCountsRequest.Count count : counts) {
			if (!productIds.add(count.productId())) {
				throw new InvalidRequestException(ErrorCode.INVENTORY_DUPLICATE_PRODUCT,
						ErrorCode.INVENTORY_DUPLICATE_PRODUCT.defaultMessage());
			}
			inventory.line(count.productId()).orElseThrow(InventoryService::notInScope);
		}
		Map<UUID, Integer> theoretical = this.stockReader.quantities(productIds, inventory.getWarehouseId());
		Instant now = Instant.now(this.clock);
		for (InventoryCountsRequest.Count count : counts) {
			InventoryLine line = inventory.line(count.productId()).orElseThrow();
			inventory.count(line, count.countedQuantity(), theoretical.getOrDefault(count.productId(), 0), now, actor,
					blankToNull(count.note()));
		}
		this.inventories.saveAndFlush(inventory);
		return traced("COUNT", inventory, "Inventaire " + inventory.getNumber() + " : " + counts.size()
				+ " comptage(s) enregistré(s)", actor);
	}

	/** Clôt le comptage et soumet l'inventaire à validation (tout doit être compté). */
	@Transactional
	public InventoryResponse submit(UUID id, UUID currentWarehouseId) {
		UUID actor = actor();
		InventorySession inventory = lock(id, currentWarehouseId);
		inventory.submit(Instant.now(this.clock), actor);
		this.inventories.saveAndFlush(inventory);
		return traced("SUBMIT", inventory, "Inventaire " + inventory.getNumber() + " soumis à validation, écart total "
				+ inventory.totalDifference(), actor);
	}

	/** Le valideur renvoie l'inventaire au comptage. */
	@Transactional
	public InventoryResponse reopen(UUID id, String comment, UUID currentWarehouseId) {
		UUID actor = actor();
		InventorySession inventory = lock(id, currentWarehouseId);
		inventory.reopen();
		this.inventories.saveAndFlush(inventory);
		return traced("REOPEN", inventory, "Inventaire " + inventory.getNumber() + " renvoyé au comptage"
				+ (StringUtils.hasText(comment) ? " : " + comment.trim() : ""), actor);
	}

	/**
	 * Valide : applique chaque écart non nul au stock (mouvement {@code INVENTORY}), dans une
	 * seule transaction, puis termine l'inventaire.
	 */
	@Transactional
	public InventoryResponse validate(UUID id, String comment, UUID currentWarehouseId) {
		UUID actor = actor();
		InventorySession inventory = lock(id, currentWarehouseId);
		if (inventory.getStatus() != InventoryStatus.VALIDATION) {
			throw new ConflictException(ErrorCode.INVENTORY_INVALID_TRANSITION, "Impossible de valider : l'inventaire "
					+ inventory.getNumber() + " est à l'étape " + inventory.getStatus() + ".");
		}

		List<InventoryLine> gaps = inventory.getLines().stream()
			.filter(line -> line.getDifference() != null && line.getDifference() != 0)
			.toList();
		String reason = "Écart d'inventaire " + inventory.getNumber();
		List<StockMovementCommand> commands = new ArrayList<>(gaps.size());
		for (InventoryLine line : gaps) {
			commands.add(StockMovementCommand
				.of(inventory.getWarehouseId(), line.getProductId(), StockMovementType.INVENTORY, line.getDifference(),
						StockSourceType.INVENTORY_SESSION, reason)
				.withSource(inventory.getId(), inventory.getNumber()));
		}
		List<StockMovement> movements = this.stock.applyMovements(commands);
		for (int i = 0; i < gaps.size(); i++) {
			gaps.get(i).applied(movements.get(i).getId());
		}
		// Lignes liées à leur mouvement pendant l'étape VALIDATION (contrôlé par la base), puis clôture.
		this.entityManager.flush();
		inventory.complete(Instant.now(this.clock), actor, blankToNull(comment));
		this.inventories.saveAndFlush(inventory);
		return traced("VALIDATE", inventory, "Inventaire " + inventory.getNumber() + " validé : " + gaps.size()
				+ " écart(s) appliqué(s), écart total " + inventory.totalDifference(), actor);
	}

	@Transactional
	public InventoryResponse cancel(UUID id, String reason, UUID currentWarehouseId) {
		UUID actor = actor();
		InventorySession inventory = lock(id, currentWarehouseId);
		inventory.cancel(Instant.now(this.clock), actor, blankToNull(reason));
		this.inventories.saveAndFlush(inventory);
		return traced("CANCEL", inventory, "Inventaire " + inventory.getNumber() + " annulé"
				+ (StringUtils.hasText(reason) ? " : " + reason.trim() : ""), actor);
	}

	/**
	 * Inventaire « en une fois » du front actuel : sélection, comptage, soumission, et
	 * validation immédiate si l'utilisateur détient {@code inventory:validate} (sinon
	 * l'inventaire attend un valideur). Une seule transaction.
	 */
	@Transactional
	public InventoryResponse createAndCount(LegacyInventoryRequest request, UUID warehouseId) {
		List<UUID> productIds = request.lines().stream().map(LegacyInventoryRequest.Line::productId).toList();
		InventoryResponse created = create(new CreateInventoryRequest(null, InventoryScope.SELECTION, null, productIds,
				request.note()), warehouseId);
		start(created.id(), warehouseId);
		recordCounts(created.id(), request.lines().stream()
			.map(line -> new InventoryCountsRequest.Count(line.productId(), line.physicalQuantity(), null))
			.toList(), warehouseId);
		InventoryResponse submitted = submit(created.id(), warehouseId);
		return ActorPermissions.current().contains(Permissions.INVENTORY_VALIDATE)
				? validate(created.id(), null, warehouseId) : submitted;
	}

	// --- Outils ------------------------------------------------------------------------------

	/**
	 * Inventaire verrouillé, relu en base, d'un dépôt accessible ; « introuvable » hors périmètre,
	 * {@code WAREHOUSE_MISMATCH} s'il est d'un autre dépôt que le dépôt courant.
	 */
	private InventorySession lock(UUID id, UUID currentWarehouseId) {
		InventorySession inventory = this.inventories.findById(id).orElseThrow(InventoryService::notFound);
		requireVisible(inventory);
		this.warehouseAccess.requireCurrent(inventory.getWarehouseId(), currentWarehouseId);
		this.entityManager.refresh(inventory, LockModeType.PESSIMISTIC_WRITE);
		this.warehouseAccess.requireAccessible(inventory.getWarehouseId());
		return inventory;
	}

	private void requireVisible(InventorySession inventory) {
		if (!this.warehouseAccess.canSee(inventory.getWarehouseId())) {
			throw notFound();
		}
	}

	/** Produits existants choisis un à un (actifs ou non : un produit désactivé peut rester en stock). */
	private List<ProductSnapshot> selection(List<UUID> productIds) {
		Set<UUID> unique = new LinkedHashSet<>();
		for (UUID productId : productIds) {
			if (!unique.add(productId)) {
				throw new InvalidRequestException(ErrorCode.INVENTORY_DUPLICATE_PRODUCT,
						ErrorCode.INVENTORY_DUPLICATE_PRODUCT.defaultMessage());
			}
		}
		Map<UUID, ProductSnapshot> found = this.products.snapshots(unique);
		if (found.size() != unique.size()) {
			throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND.defaultMessage());
		}
		return unique.stream().map(found::get).toList();
	}

	private InventoryResponse traced(String action, InventorySession inventory, String details, UUID actor) {
		InventoryResponse response = toResponse(inventory);
		this.audit.success(action, "INVENTORY_SESSION", inventory.getId().toString(), inventory.getNumber(), details, response,
				inventory.getWarehouseId());
		return response;
	}

	private String nextNumber() {
		return "INV-" + LocalDate.now(this.clock).getYear() + "-"
				+ String.format(Locale.ROOT, "%06d", this.inventories.nextNumber());
	}

	private static UUID actor() {
		return ActorIdentity.currentUserId().orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.INVENTORY_NOT_FOUND, ErrorCode.INVENTORY_NOT_FOUND.defaultMessage());
	}

	private static InvalidRequestException notInScope() {
		return new InvalidRequestException(ErrorCode.INVENTORY_PRODUCT_NOT_IN_SCOPE,
				ErrorCode.INVENTORY_PRODUCT_NOT_IN_SCOPE.defaultMessage());
	}

	static InventoryResponse toResponse(InventorySession inventory) {
		List<InventoryResponse.Line> lines = inventory.getLines().stream()
			.map(line -> new InventoryResponse.Line(line.getId(), line.getLineNumber(), line.getProductId(),
					line.getProductSku(), line.getProductName(), line.getTheoreticalQuantity(), line.getCountedQuantity(),
					line.getDifference(), line.getCountedAt(), line.getCountedBy(), line.getNote(),
					line.getStockMovementId()))
			.toList();
		return new InventoryResponse(inventory.getId(), inventory.getNumber(), inventory.getStatus(),
				inventory.getWarehouseId(), inventory.getScope(), inventory.getCategoryId(), inventory.getNote(),
				inventory.getCreatedAt(), inventory.getCreatedBy(), inventory.getStartedAt(), inventory.getSubmittedAt(),
				inventory.getSubmittedBy(), inventory.getValidatedAt(), inventory.getValidatedBy(),
				inventory.getValidationComment(), inventory.getCancelledAt(), inventory.getCancellationReason(),
				lines.size(), inventory.countedCount(), inventory.totalDifference(), lines);
	}

}
