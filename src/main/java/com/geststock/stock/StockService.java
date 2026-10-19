package com.geststock.stock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.common.security.ActorIdentity;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.exception.UnauthorizedException;
import com.geststock.product.ProductService;
import com.geststock.product.ProductSnapshot;
import com.geststock.warehouse.WarehouseService;

/**
 * <b>Unique point d'écriture du stock.</b> Toute variation (réception, vente, retour,
 * annulation, ajustement, inventaire, transfert) passe par {@link #applyMovements(List)}.
 *
 * <p>Algorithme, dans une seule transaction PostgreSQL :
 * <ol>
 * <li>contrôle des commandes (sens de la quantité selon le type), du dépôt (actif) et du
 * produit (existant) ;</li>
 * <li>création à zéro des niveaux absents, puis verrou {@code SELECT … FOR UPDATE} de chaque
 * niveau concerné <b>dans l'ordre (dépôt, produit)</b> : deux transactions concurrentes se
 * sérialisent sans jamais s'interbloquer ;</li>
 * <li>pour chaque commande, dans l'ordre : refus si la quantité deviendrait négative
 * ({@code STOCK_INSUFFICIENT}), écriture du mouvement (avec l'utilisateur courant et le solde
 * après mouvement), puis mise à jour du niveau.</li>
 * </ol>
 * Au moindre refus, toute l'opération est annulée (rollback) : aucun mouvement partiel.
 * Appelée depuis un autre module, elle s'exécute dans la transaction de l'appelant
 * (propagation {@code REQUIRED}) : une vente qui échoue après coup annule aussi son stock.
 *
 * <p>Pas de stock négatif : aucune règle métier ne l'autorise aujourd'hui. La contrainte
 * {@code ck_stock_level_quantity} le garantit aussi en base.
 *
 * <p>Les permissions sont contrôlées par les points d'entrée (contrôleurs, services des
 * modules appelants) ; ce service exige seulement un utilisateur authentifié, enregistré
 * comme auteur de chaque mouvement.
 */
@Service
@Transactional
public class StockService {

	private final EntityManager entityManager;

	private final StockMovementRepository movements;

	private final ProductService products;

	private final WarehouseService warehouses;

	private final AuditService audit;

	private final Clock clock;

	StockService(EntityManager entityManager, StockMovementRepository movements, ProductService products,
			WarehouseService warehouses, AuditService audit, Clock clock) {
		this.entityManager = entityManager;
		this.movements = movements;
		this.products = products;
		this.warehouses = warehouses;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * Mouvement <b>manuel</b> saisi par un utilisateur (entrée, perte, ajustement) : appliqué
	 * puis tracé dans le journal d'audit, dans la même transaction. Les mouvements produits par
	 * une pièce (vente, réception…) sont tracés par la pièce.
	 */
	public StockMovement applyManualMovement(StockMovementCommand command) {
		if (command.sourceType() != StockSourceType.MANUAL) {
			throw new IllegalArgumentException("Mouvement manuel attendu : " + command.sourceType());
		}
		StockMovement movement = applyMovement(command);
		auditMovement("CREATE", movement, movement.getId(),
				"Mouvement manuel " + movement.getType() + " de " + signed(movement.getQuantity()));
		return movement;
	}

	/** Un seul mouvement ; voir {@link #applyMovements(List)}. */
	public StockMovement applyMovement(StockMovementCommand command) {
		return applyMovements(List.of(command)).get(0);
	}

	/**
	 * Applique des mouvements de façon atomique : tous ou aucun.
	 *
	 * @return les mouvements enregistrés, dans l'ordre des commandes
	 * @throws InvalidRequestException {@code INVALID_STOCK_QUANTITY} (sens incompatible avec le
	 *                                 type), {@code UNKNOWN_WAREHOUSE} (dépôt inconnu ou inactif)
	 * @throws NotFoundException       {@code PRODUCT_NOT_FOUND}
	 * @throws BusinessRuleException   {@code STOCK_INSUFFICIENT}
	 */
	public List<StockMovement> applyMovements(List<StockMovementCommand> commands) {
		if (commands.isEmpty()) {
			return List.of();
		}
		Set<StockLevelId> keys = new LinkedHashSet<>();
		commands.forEach(command -> keys.add(command.key()));
		return execute(keys, levels -> commands);
	}

	/**
	 * Inventaire : aligne le stock d'un dépôt sur les quantités comptées et validées. L'écart
	 * (compté − théorique) est calculé <b>sous verrou</b>, donc sur la quantité réelle au
	 * moment de la validation ; un mouvement {@code INVENTORY} est écrit pour chaque écart non
	 * nul.
	 *
	 * @param counted quantité comptée par produit (≥ 0)
	 * @return les mouvements d'écart (vide si tout concorde)
	 */
	public List<StockMovement> applyInventoryCounts(UUID warehouseId, Map<UUID, Integer> counted, UUID sessionId,
			String sessionReference) {
		counted.forEach((productId, quantity) -> {
			if (quantity == null || quantity < 0) {
				throw new InvalidRequestException(ErrorCode.INVALID_STOCK_QUANTITY,
						"Quantité comptée invalide pour le produit " + productId + ".");
			}
		});
		Set<StockLevelId> keys = new LinkedHashSet<>();
		counted.keySet().forEach(productId -> keys.add(new StockLevelId(warehouseId, productId)));
		return execute(keys, levels -> {
			List<StockMovementCommand> commands = new ArrayList<>();
			counted.forEach((productId, quantity) -> {
				int difference = quantity - levels.get(new StockLevelId(warehouseId, productId)).getQuantity();
				if (difference != 0) {
					commands.add(StockMovementCommand
						.of(warehouseId, productId, StockMovementType.INVENTORY, difference,
								StockSourceType.INVENTORY_SESSION, "Écart d'inventaire")
						.withSource(sessionId, sessionReference));
				}
			});
			return commands;
		});
	}

	/**
	 * Transfert entre deux dépôts : sortie {@code TRANSFER_OUT} du dépôt source et entrée
	 * {@code TRANSFER_IN} dans le dépôt destination, dans la même transaction et sous le même
	 * identifiant de corrélation. Sans pièce numérotée : l'API passe par
	 * {@link StockTransferService}, qui enregistre le transfert et son reçu.
	 *
	 * @return les deux mouvements (sortie, entrée)
	 */
	public List<StockMovement> transfer(UUID fromWarehouseId, UUID toWarehouseId, UUID productId, int quantity,
			String reason) {
		UUID transferId = UUID.randomUUID();
		String note = StringUtils.hasText(reason) ? reason.trim() : "Transfert entre dépôts";
		List<StockMovement> written = moveBetween(fromWarehouseId, toWarehouseId, Map.of(productId, quantity), note,
				transferId, transferId, null);
		StockMovement out = written.get(0);
		StockMovement in = written.get(1);
		this.audit.record(AuditEvent.of("TRANSFER", "WAREHOUSE_TRANSFER", transferId, productLabel(productId))
			.details("Transfert de " + quantity + " " + productLabel(productId) + " — " + note)
			.before(Map.of("sourceQuantity", out.getBalanceAfter() + quantity,
					"destinationQuantity", in.getBalanceAfter() - quantity))
			.after(Map.of("sourceQuantity", out.getBalanceAfter(), "destinationQuantity", in.getBalanceAfter()))
			.meta("fromWarehouseId", fromWarehouseId)
			.meta("toWarehouseId", toWarehouseId)
			.meta("productId", productId)
			.warehouse(fromWarehouseId));
		return written;
	}

	/**
	 * Les mouvements d'un transfert (ou de son retour), sans trace d'audit : l'appelant trace
	 * l'opération. Pour chaque produit, sortie {@code TRANSFER_OUT} de {@code fromWarehouseId}
	 * puis entrée {@code TRANSFER_IN} dans {@code toWarehouseId} ; tous atomiques (un produit
	 * insuffisant annule l'ensemble).
	 *
	 * @param quantities    quantité à transférer par produit, dans l'ordre des lignes
	 * @param sourceId      pièce de transfert (ou identifiant du transfert sans pièce)
	 * @param correlationId regroupe les mouvements de cette opération
	 * @param reference     numéro de la pièce, ou {@code null}
	 * @return les mouvements, par produit : sortie puis entrée
	 */
	List<StockMovement> moveBetween(UUID fromWarehouseId, UUID toWarehouseId, Map<UUID, Integer> quantities,
			String note, UUID sourceId, UUID correlationId, String reference) {
		if (fromWarehouseId.equals(toWarehouseId)) {
			throw new InvalidRequestException(ErrorCode.SAME_WAREHOUSE_TRANSFER,
					ErrorCode.SAME_WAREHOUSE_TRANSFER.defaultMessage());
		}
		if (quantities.isEmpty() || quantities.values().stream().anyMatch((quantity) -> quantity == null || quantity <= 0)) {
			throw new InvalidRequestException(ErrorCode.INVALID_STOCK_QUANTITY,
					ErrorCode.INVALID_STOCK_QUANTITY.defaultMessage());
		}
		List<StockMovementCommand> commands = new ArrayList<>(quantities.size() * 2);
		quantities.forEach((productId, quantity) -> {
			commands.add(StockMovementCommand.of(fromWarehouseId, productId, StockMovementType.TRANSFER_OUT, -quantity,
					StockSourceType.TRANSFER, note).withSource(sourceId, reference).withCorrelation(correlationId));
			commands.add(StockMovementCommand.of(toWarehouseId, productId, StockMovementType.TRANSFER_IN, quantity,
					StockSourceType.TRANSFER, note).withSource(sourceId, reference).withCorrelation(correlationId));
		});
		return applyMovements(commands);
	}

	/**
	 * Annule un mouvement <b>manuel</b> par un mouvement {@code REVERSAL} de quantité opposée.
	 * Les mouvements produits par une pièce (vente, réception, transfert, inventaire) s'annulent
	 * par la pièce elle-même (ex. {@code SALE_CANCELLATION}). Un mouvement n'est annulable
	 * qu'une fois ; l'annulation d'une entrée déjà consommée est refusée (stock insuffisant).
	 */
	public StockMovement reverse(UUID movementId, String reason) {
		StockMovement original = this.movements.findById(movementId)
			.orElseThrow(() -> new NotFoundException(ErrorCode.STOCK_MOVEMENT_NOT_FOUND,
					ErrorCode.STOCK_MOVEMENT_NOT_FOUND.defaultMessage()));
		if (original.getType() == StockMovementType.REVERSAL || original.getSourceType() != StockSourceType.MANUAL) {
			throw new ConflictException(ErrorCode.MOVEMENT_NOT_REVERSIBLE, ErrorCode.MOVEMENT_NOT_REVERSIBLE.defaultMessage());
		}
		if (this.movements.existsByReversalOfId(movementId)) {
			throw new ConflictException(ErrorCode.MOVEMENT_ALREADY_REVERSED,
					ErrorCode.MOVEMENT_ALREADY_REVERSED.defaultMessage());
		}
		String note = StringUtils.hasText(reason) ? reason.trim() : "Annulation du mouvement";
		StockMovement reversal = applyMovement(new StockMovementCommand(original.getWarehouseId(),
				original.getProductId(), StockMovementType.REVERSAL, -original.getQuantity(), note,
				StockSourceType.MANUAL, original.getId(), original.getSourceReference(), original.getUnitCost(),
				original.getCorrelationId(), original.getId()));
		auditMovement("REVERSAL", reversal, original.getId(), "Annulation du mouvement " + original.getType() + " de "
				+ signed(original.getQuantity()) + " — " + note);
		return reversal;
	}

	private void auditMovement(String action, StockMovement movement, UUID entityId, String details) {
		String product = productLabel(movement.getProductId());
		this.audit.record(AuditEvent.of(action, "STOCK_MOVEMENT", entityId, product)
			.details(details + " sur " + product
					+ (StringUtils.hasText(movement.getReason()) ? " (" + movement.getReason() + ")" : ""))
			.before(Map.of("quantity", movement.getBalanceAfter() - movement.getQuantity()))
			.after(Map.of("quantity", movement.getBalanceAfter()))
			.meta("movementId", movement.getId())
			.meta("type", movement.getType())
			.meta("productId", movement.getProductId())
			.warehouse(movement.getWarehouseId()));
	}

	private String productLabel(UUID productId) {
		ProductSnapshot product = this.products.snapshots(List.of(productId)).get(productId);
		return product != null ? product.sku() + " · " + product.name() : productId.toString();
	}

	private static String signed(int quantity) {
		return (quantity > 0 ? "+" : "") + quantity;
	}

	// --- Cœur ---------------------------------------------------------------------------

	/**
	 * Verrouille les niveaux {@code keys}, calcule les commandes à partir des quantités
	 * verrouillées, puis les applique.
	 */
	private List<StockMovement> execute(Set<StockLevelId> keys,
			Function<Map<StockLevelId, StockLevel>, List<StockMovementCommand>> planner) {
		UUID actor = ActorIdentity.currentUserId()
			.orElseThrow(() -> new UnauthorizedException(ErrorCode.UNAUTHENTICATED));

		Set<UUID> warehouseIds = new LinkedHashSet<>();
		Set<UUID> productIds = new LinkedHashSet<>();
		keys.forEach(key -> {
			warehouseIds.add(key.getWarehouseId());
			productIds.add(key.getProductId());
		});
		this.warehouses.findActiveByIds(warehouseIds);
		Map<UUID, ProductSnapshot> productById = this.products.snapshots(productIds);
		if (productById.size() != productIds.size()) {
			throw new NotFoundException(ErrorCode.PRODUCT_NOT_FOUND, ErrorCode.PRODUCT_NOT_FOUND.defaultMessage());
		}

		Map<StockLevelId, StockLevel> levels = lockInOrder(keys);
		List<StockMovementCommand> commands = planner.apply(levels);
		Instant now = Instant.now(this.clock);
		List<StockMovement> written = new ArrayList<>(commands.size());
		for (StockMovementCommand command : commands) {
			if (!command.type().accepts(command.quantity())) {
				throw new InvalidRequestException(ErrorCode.INVALID_STOCK_QUANTITY,
						"Quantité " + command.quantity() + " incompatible avec un mouvement " + command.type() + ".");
			}
			StockLevel level = levels.get(command.key());
			if (level == null) {
				throw new IllegalStateException("Niveau non verrouillé : " + command.key());
			}
			int balance = level.getQuantity() + command.quantity();
			if (balance < 0) {
				Map<String, Object> details = new LinkedHashMap<>();
				details.put("warehouseId", command.warehouseId());
				details.put("productId", command.productId());
				details.put("productSku", productById.get(command.productId()).sku());
				details.put("available", level.getQuantity());
				details.put("requested", -command.quantity());
				throw new BusinessRuleException(ErrorCode.STOCK_INSUFFICIENT, ErrorCode.STOCK_INSUFFICIENT.defaultMessage(),
						details);
			}
			BigDecimal unitCost = command.unitCost() != null ? command.unitCost()
					: productById.get(command.productId()).purchasePrice();
			StockMovement movement = this.movements.save(new StockMovement(command, balance, unitCost, now, actor));
			// Le mouvement doit exister en base avant la mise à jour du niveau qui le désigne
			// (trigger fn_stock_level_guard) : écriture immédiate, mouvement par mouvement.
			this.entityManager.flush();
			level.apply(movement);
			this.entityManager.flush();
			written.add(movement);
		}
		return written;
	}

	/**
	 * Crée à zéro les niveaux absents puis les verrouille, toujours dans l'ordre (dépôt,
	 * produit). L'état est relu depuis la base sous verrou.
	 */
	private Map<StockLevelId, StockLevel> lockInOrder(Set<StockLevelId> keys) {
		Map<StockLevelId, StockLevel> levels = new LinkedHashMap<>();
		for (StockLevelId key : new TreeSet<>(keys)) {
			this.entityManager.createNativeQuery("""
					insert into stock_level (warehouse_id, product_id) values (:warehouseId, :productId)
					on conflict do nothing""")
				.setParameter("warehouseId", key.getWarehouseId())
				.setParameter("productId", key.getProductId())
				.executeUpdate();
			StockLevel level = this.entityManager.find(StockLevel.class, key);
			this.entityManager.refresh(level, LockModeType.PESSIMISTIC_WRITE);
			levels.put(key, level);
		}
		return levels;
	}

}
