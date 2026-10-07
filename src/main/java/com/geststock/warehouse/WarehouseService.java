package com.geststock.warehouse;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.common.api.ActiveStatus;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorPermissions;
import com.geststock.common.security.ActorWarehouseScope;
import com.geststock.common.security.Permissions;
import com.geststock.exception.BusinessRuleException;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.warehouse.dto.WarehouseFilter;
import com.geststock.warehouse.dto.WarehouseRequest;
import com.geststock.warehouse.dto.WarehouseResponse;

/**
 * Dépôts et magasins (API publique du module {@code warehouse}).
 *
 * <p>Règles :
 * <ul>
 * <li>code unique (majuscules, généré si absent) et nom unique sans tenir compte de la casse ;</li>
 * <li>désactivation plutôt que suppression : stock, ventes et mouvements y restent rattachés ;</li>
 * <li>un seul dépôt principal, toujours actif ; il ne peut être désactivé qu'après avoir
 * désigné un autre dépôt principal ;</li>
 * <li>visibilité : {@code warehouse:all} ou {@code warehouse:manage} voient tous les dépôts ;
 * les autres utilisateurs ne voient que les dépôts actifs de leur périmètre
 * ({@link WarehouseAccess}). Un dépôt hors périmètre répond 404.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class WarehouseService {

	static final SortWhitelist SORTS = new SortWhitelist(
			Map.of("name", "name", "code", "code", "type", "type", "createdAt", "createdAt"), Sort.by("name"));

	private static final Map<WarehouseType, String> CODE_PREFIXES = Map.of(WarehouseType.STORE, "MAG-",
			WarehouseType.WAREHOUSE, "DEP-");

	private final WarehouseRepository repository;

	private final WarehouseAccess access;

	WarehouseService(WarehouseRepository repository, WarehouseAccess access) {
		this.repository = repository;
		this.access = access;
	}

	public Page<WarehouseResponse> search(WarehouseFilter filter, PageQuery query) {
		Specification<Warehouse> spec = Specification.unrestricted();
		if (StringUtils.hasText(filter.q())) {
			String pattern = "%" + filter.q().trim().toLowerCase(Locale.ROOT) + "%";
			spec = spec.and((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("name")), pattern),
					cb.like(cb.lower(root.get("code")), pattern)));
		}
		if (filter.type() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("type"), filter.type()));
		}
		Set<UUID> visible = visibleIds();
		ActiveStatus status = visible == null && filter.status() != null ? filter.status() : ActiveStatus.ACTIVE;
		Boolean active = status.asActiveFlag();
		if (active != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("active"), active));
		}
		if (visible != null) {
			spec = spec.and(visible.isEmpty() ? (root, q, cb) -> cb.disjunction()
					: (root, q, cb) -> root.get("id").in(visible));
		}
		return this.repository.findAll(spec, query.toPageable(SORTS)).map(WarehouseService::toResponse);
	}

	public WarehouseResponse get(UUID id) {
		Warehouse warehouse = load(id);
		Set<UUID> visible = visibleIds();
		if (visible != null && (!warehouse.isActive() || !visible.contains(id))) {
			throw notFound();
		}
		return toResponse(warehouse);
	}

	@Transactional
	public WarehouseResponse create(WarehouseRequest request) {
		String name = request.name().trim();
		WarehouseType type = request.type() != null ? request.type() : WarehouseType.STORE;
		String code = StringUtils.hasText(request.code()) ? normalizeCode(request.code()) : nextCode(type);
		requireUniqueCode(code, null);
		requireUniqueName(name, null);
		Warehouse warehouse = new Warehouse(code, name, type);
		warehouse.changeContact(blankToNull(request.address()), blankToNull(request.phone()));
		return toResponse(this.repository.saveAndFlush(warehouse));
	}

	/** Modification ; un code ou un type absent conserve la valeur actuelle. */
	@Transactional
	public WarehouseResponse update(UUID id, WarehouseRequest request) {
		Warehouse warehouse = load(id);
		String name = request.name().trim();
		String code = StringUtils.hasText(request.code()) ? normalizeCode(request.code()) : warehouse.getCode();
		requireUniqueCode(code, id);
		requireUniqueName(name, id);
		warehouse.update(code, name, request.type() != null ? request.type() : warehouse.getType());
		warehouse.changeContact(blankToNull(request.address()), blankToNull(request.phone()));
		return toResponse(this.repository.saveAndFlush(warehouse));
	}

	/**
	 * Active ou désactive. Un dépôt désactivé n'est plus sélectionnable comme dépôt courant ni
	 * attribuable ; ses données (stock, pièces) sont conservées.
	 */
	@Transactional
	public WarehouseResponse setActive(UUID id, boolean active) {
		Warehouse warehouse = load(id);
		if (active) {
			warehouse.activate();
		}
		else {
			if (warehouse.isMain()) {
				throw new ConflictException(ErrorCode.MAIN_WAREHOUSE_REQUIRED,
						ErrorCode.MAIN_WAREHOUSE_REQUIRED.defaultMessage());
			}
			warehouse.deactivate();
		}
		return toResponse(this.repository.saveAndFlush(warehouse));
	}

	/** Désigne le dépôt principal (dépôt de repli des utilisateurs sans affectation). */
	@Transactional
	public WarehouseResponse setMain(UUID id) {
		Warehouse target = load(id);
		if (!target.isActive()) {
			throw new BusinessRuleException(ErrorCode.WAREHOUSE_INACTIVE, ErrorCode.WAREHOUSE_INACTIVE.defaultMessage());
		}
		if (target.isMain()) {
			return toResponse(target);
		}
		this.repository.findByMainTrue().ifPresent(current -> {
			current.unmarkMain();
			// Index unique partiel uk_warehouse_main : l'ancien principal doit être écrit d'abord.
			this.repository.saveAndFlush(current);
		});
		target.markMain();
		return toResponse(this.repository.saveAndFlush(target));
	}

	/**
	 * Dépôts actifs correspondant aux identifiants demandés (affectation des utilisateurs).
	 *
	 * @throws InvalidRequestException {@code UNKNOWN_WAREHOUSE} si l'un est inconnu ou inactif
	 */
	public Set<Warehouse> findActiveByIds(Collection<UUID> ids) {
		Set<UUID> distinct = new LinkedHashSet<>(ids);
		List<Warehouse> found = this.repository.findAllByIdInAndActiveTrue(distinct);
		if (found.size() != distinct.size()) {
			throw new InvalidRequestException(ErrorCode.UNKNOWN_WAREHOUSE, ErrorCode.UNKNOWN_WAREHOUSE.defaultMessage());
		}
		return new LinkedHashSet<>(found);
	}

	/** Dépôt actif, ou {@code UNKNOWN_WAREHOUSE}. */
	public Warehouse getActive(UUID id) {
		return findActiveByIds(Set.of(id)).iterator().next();
	}

	/** Dépôts visibles par l'utilisateur courant, ou {@code null} s'il les voit tous. */
	private Set<UUID> visibleIds() {
		if (ActorPermissions.current().contains(Permissions.WAREHOUSE_MANAGE)) {
			return null;
		}
		return this.access.accessibleIds(ActorWarehouseScope.current());
	}

	private String nextCode(WarehouseType type) {
		String prefix = CODE_PREFIXES.get(type);
		long n = this.repository.count() + 1;
		String code;
		do {
			code = prefix + String.format(Locale.ROOT, "%02d", n++);
		}
		while (this.repository.existsByCode(code));
		return code;
	}

	private Warehouse load(UUID id) {
		return this.repository.findById(id).orElseThrow(WarehouseService::notFound);
	}

	private void requireUniqueCode(String code, UUID excludedId) {
		if (this.repository.existsByCodeExcluding(code, excludedId)) {
			throw new ConflictException(ErrorCode.WAREHOUSE_CODE_ALREADY_USED,
					ErrorCode.WAREHOUSE_CODE_ALREADY_USED.defaultMessage());
		}
	}

	private void requireUniqueName(String name, UUID excludedId) {
		if (this.repository.existsByNameIgnoringCase(name, excludedId)) {
			throw new ConflictException(ErrorCode.WAREHOUSE_NAME_ALREADY_USED,
					ErrorCode.WAREHOUSE_NAME_ALREADY_USED.defaultMessage());
		}
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.WAREHOUSE_NOT_FOUND, ErrorCode.WAREHOUSE_NOT_FOUND.defaultMessage());
	}

	static WarehouseResponse toResponse(Warehouse warehouse) {
		return new WarehouseResponse(warehouse.getId(), warehouse.getCode(), warehouse.getName(), warehouse.getType(),
				warehouse.getAddress(), warehouse.getPhone(), warehouse.isMain(), warehouse.isActive(),
				warehouse.getCreatedAt(), warehouse.getUpdatedAt());
	}

	private static String normalizeCode(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
