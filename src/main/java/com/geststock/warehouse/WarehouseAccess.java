package com.geststock.warehouse;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.common.security.ActorWarehouseScope;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.exception.InvalidRequestException;

/**
 * Règles d'accès aux dépôts. Le périmètre vient du serveur (affectations relues en base et
 * permission {@code warehouse:all}) ; l'en-tête envoyé par le front n'est qu'un choix parmi
 * les dépôts autorisés.
 *
 * <ul>
 * <li>{@code warehouse:all} : tous les dépôts actifs ;</li>
 * <li>sinon : dépôts attribués et dépôt par défaut ;</li>
 * <li>utilisateur sans aucune affectation : dépôt principal (fonctionnement mono-magasin
 * actuel du front).</li>
 * </ul>
 *
 * <p>Résolution du dépôt courant ({@code X-Warehouse-Id}). Un dépôt désigné n'est jamais
 * remplacé par un autre :
 * <ol>
 * <li>en-tête absent : dépôt par défaut de l'utilisateur, puis premier dépôt attribué actif,
 * puis dépôt principal s'il lui est accessible ; aucun : 403 {@code NO_WAREHOUSE_AVAILABLE} ;</li>
 * <li>en-tête qui n'est pas un UUID (ex. ancien identifiant du mock {@code wh_1}) : 400
 * {@code INVALID_WAREHOUSE_ID} ;</li>
 * <li>dépôt inconnu : 400 {@code UNKNOWN_WAREHOUSE} ;</li>
 * <li>dépôt hors du périmètre de l'utilisateur, actif ou non : 403
 * {@code WAREHOUSE_ACCESS_DENIED} (l'état d'un dépôt inaccessible n'est pas révélé) ;</li>
 * <li>dépôt accessible mais désactivé : 403 {@code WAREHOUSE_INACTIVE} ;</li>
 * <li>sinon : ce dépôt.</li>
 * </ol>
 */
@Component
@Transactional(readOnly = true)
public class WarehouseAccess {

	private final WarehouseRepository repository;

	WarehouseAccess(WarehouseRepository repository) {
		this.repository = repository;
	}

	/** Dépôt courant de l'utilisateur authentifié, d'après l'en-tête {@code X-Warehouse-Id}. */
	public WarehouseContext resolve(String header) {
		return resolve(header, ActorWarehouseScope.current());
	}

	/**
	 * Dépôt désigné explicitement (corps de requête, ex. dépôt destination d'un transfert) :
	 * il doit être actif et accessible à l'utilisateur courant.
	 */
	public WarehouseContext requireAccessible(UUID id) {
		Warehouse warehouse = this.repository.findById(id)
			.filter(Warehouse::isActive)
			.orElseThrow(() -> new InvalidRequestException(ErrorCode.UNKNOWN_WAREHOUSE,
					ErrorCode.UNKNOWN_WAREHOUSE.defaultMessage()));
		requireAccess(ActorWarehouseScope.current(), warehouse);
		return WarehouseContext.of(warehouse);
	}

	/**
	 * Écriture ou action métier sur une pièce existante (ou dépôt envoyé dans un corps de
	 * requête) : elle se fait depuis son dépôt. Le dépôt courant est celui résolu par le serveur
	 * ({@code @CurrentWarehouse}) ; un dépôt différent, même accessible, est refusé.
	 *
	 * <p>À appeler <b>après</b> le contrôle de visibilité : une pièce hors périmètre reste
	 * introuvable (404), seule une pièce visible d'un autre dépôt reçoit ce refus.
	 * @throws ConflictException 409 {@code WAREHOUSE_MISMATCH}
	 */
	public void requireCurrent(UUID recordWarehouseId, UUID currentWarehouseId) {
		if (currentWarehouseId == null || !currentWarehouseId.equals(recordWarehouseId)) {
			throw new ConflictException(ErrorCode.WAREHOUSE_MISMATCH, ErrorCode.WAREHOUSE_MISMATCH.defaultMessage());
		}
	}

	/**
	 * Dépôt envoyé dans un corps de requête (facultatif) : absent, c'est le dépôt courant ;
	 * présent, il doit être le dépôt courant. Le corps ne remplace jamais le contexte du serveur.
	 * @throws ConflictException 409 {@code WAREHOUSE_MISMATCH}
	 */
	public UUID requireCurrentIfPresent(UUID requestedWarehouseId, UUID currentWarehouseId) {
		if (requestedWarehouseId != null) {
			requireCurrent(requestedWarehouseId, currentWarehouseId);
		}
		return currentWarehouseId;
	}

	/**
	 * {@code true} si l'utilisateur courant peut consulter les données de ce dépôt (même
	 * désactivé), par exemple l'historique d'un mouvement.
	 */
	public boolean canSee(UUID warehouseId) {
		Set<UUID> ids = accessibleIds(ActorWarehouseScope.current());
		return ids == null || ids.contains(warehouseId);
	}

	/**
	 * Dépôts dont l'utilisateur courant peut consulter les données, ou {@code null} s'il les voit
	 * tous ({@code warehouse:all}). Pour les listes qui ne dépendent pas du dépôt courant.
	 */
	public Set<UUID> visibleWarehouseIds() {
		return accessibleIds(ActorWarehouseScope.current());
	}

	/** {@code true} si l'utilisateur courant voit tous les dépôts ({@code warehouse:all}). */
	public boolean seesAllWarehouses() {
		return ActorWarehouseScope.current().allWarehouses();
	}

	WarehouseContext resolve(String header, ActorWarehouseScope scope) {
		if (!StringUtils.hasText(header)) {
			return fallback(scope).map(WarehouseContext::of)
				.orElseThrow(() -> new ForbiddenOperationException(ErrorCode.NO_WAREHOUSE_AVAILABLE,
						ErrorCode.NO_WAREHOUSE_AVAILABLE.defaultMessage()));
		}
		Warehouse requested = this.repository.findById(parse(header))
			.orElseThrow(() -> new InvalidRequestException(ErrorCode.UNKNOWN_WAREHOUSE,
					ErrorCode.UNKNOWN_WAREHOUSE.defaultMessage()));
		requireAccess(scope, requested);
		if (!requested.isActive()) {
			throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_INACTIVE,
					ErrorCode.WAREHOUSE_INACTIVE.defaultMessage());
		}
		return WarehouseContext.of(requested);
	}

	/**
	 * Identifiants des dépôts accessibles, ou {@code null} si l'utilisateur les voit tous
	 * ({@code warehouse:all}).
	 */
	Set<UUID> accessibleIds(ActorWarehouseScope scope) {
		if (scope.allWarehouses()) {
			return null;
		}
		if (scope.unassigned()) {
			return this.repository.findByMainTrue().map(w -> Set.of(w.getId())).orElse(Set.of());
		}
		Set<UUID> ids = new LinkedHashSet<>(scope.warehouseIds());
		if (scope.defaultWarehouseId() != null) {
			ids.add(scope.defaultWarehouseId());
		}
		return ids;
	}

	boolean canAccess(ActorWarehouseScope scope, Warehouse warehouse) {
		Set<UUID> ids = accessibleIds(scope);
		return ids == null || ids.contains(warehouse.getId());
	}

	private void requireAccess(ActorWarehouseScope scope, Warehouse warehouse) {
		if (!canAccess(scope, warehouse)) {
			throw new ForbiddenOperationException(ErrorCode.WAREHOUSE_ACCESS_DENIED,
					ErrorCode.WAREHOUSE_ACCESS_DENIED.defaultMessage());
		}
	}

	private Optional<Warehouse> fallback(ActorWarehouseScope scope) {
		if (scope.defaultWarehouseId() != null) {
			Optional<Warehouse> preferred = this.repository.findById(scope.defaultWarehouseId())
				.filter(Warehouse::isActive);
			if (preferred.isPresent()) {
				return preferred;
			}
		}
		if (!scope.warehouseIds().isEmpty()) {
			Optional<Warehouse> assigned = this.repository.findAllByIdInAndActiveTrue(scope.warehouseIds())
				.stream()
				.min(Comparator.comparing(Warehouse::getName, String.CASE_INSENSITIVE_ORDER));
			if (assigned.isPresent()) {
				return assigned;
			}
		}
		if (scope.allWarehouses() || scope.unassigned()) {
			return this.repository.findByMainTrue();
		}
		return Optional.empty();
	}

	private static UUID parse(String header) {
		try {
			return UUID.fromString(header.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidRequestException(ErrorCode.INVALID_WAREHOUSE_ID,
					ErrorCode.INVALID_WAREHOUSE_ID.defaultMessage());
		}
	}

}
