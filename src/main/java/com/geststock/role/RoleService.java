package com.geststock.role;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.common.security.ActorPermissions;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.ForbiddenOperationException;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.permission.Permission;
import com.geststock.permission.PermissionService;
import com.geststock.role.dto.CreateRoleRequest;
import com.geststock.role.dto.RoleResponse;
import com.geststock.role.dto.UpdateRoleRequest;

/**
 * Rôles et matrice rôle → permissions (API publique du module {@code role}).
 *
 * <p>Règles :
 * <ul>
 * <li>{@code ADMIN} détient toujours <b>toutes</b> les permissions, y compris celles
 * ajoutées par une migration future, et ne peut pas être modifié ;</li>
 * <li>les rôles système ne peuvent pas être supprimés ; un rôle attribué non plus ;</li>
 * <li>pas d'escalade : on ne peut ajouter à un rôle que des permissions que l'on détient ;</li>
 * <li>aucun rôle = aucune permission : rien n'est accordé par défaut.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class RoleService {

	/** Code du rôle administrateur. */
	public static final String ADMIN = "ADMIN";

	private final RoleRepository repository;

	private final PermissionService permissionService;

	private final AuditService audit;

	/** Cache rôle → permissions, lu à chaque requête authentifiée ; vidé après chaque modification. */
	private final Map<String, Set<String>> permissionsByRole = new ConcurrentHashMap<>();

	RoleService(RoleRepository repository, PermissionService permissionService, AuditService audit) {
		this.repository = repository;
		this.permissionService = permissionService;
		this.audit = audit;
	}

	// --- Lecture --------------------------------------------------------------------------

	public List<RoleResponse> list() {
		return this.repository.findAllWithPermissions().stream().map(this::toResponse).toList();
	}

	public RoleResponse get(UUID id) {
		return toResponse(load(id));
	}

	/**
	 * Rôles correspondant aux codes demandés (casse ignorée).
	 *
	 * @throws InvalidRequestException {@code UNKNOWN_ROLE} si un code n'existe pas
	 */
	public Set<Role> findAllByCodes(Collection<String> codes) {
		Set<String> normalized = codes.stream()
			.map(code -> code.trim().toUpperCase(Locale.ROOT))
			.collect(Collectors.toCollection(LinkedHashSet::new));
		List<Role> roles = this.repository.findAllByCodeIn(normalized);
		if (roles.size() != normalized.size()) {
			Set<String> unknown = new TreeSet<>(normalized);
			roles.forEach(role -> unknown.remove(role.getCode()));
			throw new InvalidRequestException(ErrorCode.UNKNOWN_ROLE, "Rôle inconnu : " + String.join(", ", unknown) + ".");
		}
		return new LinkedHashSet<>(roles);
	}

	/** Permissions accordées par un ensemble de rôles. Aucun rôle = aucune permission. */
	public Set<String> permissionsOf(Collection<String> roleCodes) {
		Set<String> permissions = new TreeSet<>();
		for (String roleCode : roleCodes) {
			permissions.addAll(this.permissionsByRole.computeIfAbsent(roleCode, this::loadPermissions));
		}
		return Collections.unmodifiableSet(permissions);
	}

	/**
	 * Vérifie que l'utilisateur courant pourrait accorder ces rôles (il détient lui-même
	 * toutes leurs permissions). Utilisé lors de l'attribution de rôles à un utilisateur.
	 *
	 * @throws ForbiddenOperationException {@code PRIVILEGE_ESCALATION}
	 */
	public void requireGrantable(Collection<Role> roles) {
		requireActorHolds(permissionsOf(roles.stream().map(Role::getCode).toList()),
				"attribuer le rôle " + roles.stream().map(Role::getCode).sorted().collect(Collectors.joining(", ")));
	}

	// --- Écriture ---------------------------------------------------------------------------

	@Transactional
	public RoleResponse create(CreateRoleRequest request) {
		if (this.repository.existsByCode(request.code())) {
			throw new ConflictException(ErrorCode.ROLE_CODE_ALREADY_USED, ErrorCode.ROLE_CODE_ALREADY_USED.defaultMessage());
		}
		Set<Permission> permissions = this.permissionService.findAllByCodes(request.permissions());
		requireActorHolds(codes(permissions), "créer ce rôle");

		Role role = new Role(request.code(), request.label().trim());
		role.describe(request.label().trim(), blankToNull(request.description()));
		role.replacePermissions(permissions);
		Role saved = this.repository.saveAndFlush(role);
		evictCacheAfterCommit();
		RoleResponse created = toResponse(saved);
		this.audit.record(AuditEvent.of("CREATE", "ROLE", saved.getId(), saved.getCode())
			.details("Création du rôle " + saved.getCode() + " (" + permissions.size() + " permission(s))")
			.after(created));
		return created;
	}

	@Transactional
	public RoleResponse update(UUID id, UpdateRoleRequest request) {
		Role role = load(id);
		RoleResponse before = toResponse(role);
		if (ADMIN.equals(role.getCode())) {
			throw new ConflictException(ErrorCode.ROLE_NOT_EDITABLE, ErrorCode.ROLE_NOT_EDITABLE.defaultMessage());
		}
		Set<Permission> permissions = this.permissionService.findAllByCodes(request.permissions());
		Set<String> added = new TreeSet<>(codes(permissions));
		added.removeAll(codes(role.getPermissions()));
		requireActorHolds(added, "ajouter ces permissions");

		Set<String> removed = new TreeSet<>(codes(role.getPermissions()));
		removed.removeAll(codes(permissions));

		role.describe(request.label().trim(), blankToNull(request.description()));
		role.replacePermissions(permissions);
		Role saved = this.repository.saveAndFlush(role);
		evictCacheAfterCommit();
		RoleResponse updated = toResponse(saved);
		this.audit.record(AuditEvent.of("UPDATE", "ROLE", id, saved.getCode())
			.details("Rôle " + saved.getCode() + " modifié ; permissions ajoutées : "
					+ (added.isEmpty() ? "aucune" : String.join(", ", added)) + " ; retirées : "
					+ (removed.isEmpty() ? "aucune" : String.join(", ", removed)))
			.before(before)
			.after(updated)
			.meta("addedPermissions", added)
			.meta("removedPermissions", removed));
		return updated;
	}

	@Transactional
	public void delete(UUID id) {
		Role role = load(id);
		if (role.isBuiltIn()) {
			throw new ConflictException(ErrorCode.ROLE_NOT_DELETABLE, ErrorCode.ROLE_NOT_DELETABLE.defaultMessage());
		}
		if (this.repository.countUsers(id) > 0) {
			throw new ConflictException(ErrorCode.ROLE_IN_USE, ErrorCode.ROLE_IN_USE.defaultMessage());
		}
		RoleResponse before = toResponse(role);
		this.repository.delete(role);
		this.repository.flush();
		evictCacheAfterCommit();
		this.audit.record(AuditEvent.of("DELETE", "ROLE", id, role.getCode())
			.details("Suppression du rôle " + role.getCode())
			.before(before));
	}

	/** Vide le cache (après une modification de la matrice hors API, ex. migration). */
	public void evictPermissionCache() {
		this.permissionsByRole.clear();
	}

	// --- Outils -----------------------------------------------------------------------------

	private Role load(UUID id) {
		return this.repository.findWithPermissionsById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.ROLE_NOT_FOUND, ErrorCode.ROLE_NOT_FOUND.defaultMessage()));
	}

	private Set<String> loadPermissions(String roleCode) {
		if (ADMIN.equals(roleCode)) {
			return Set.copyOf(this.permissionService.allCodes());
		}
		return this.repository.findWithPermissionsByCode(roleCode)
			.map(role -> codes(role.getPermissions()))
			.orElse(Set.of());
	}

	private void requireActorHolds(Set<String> permissions, String action) {
		Set<String> missing = new TreeSet<>(permissions);
		missing.removeAll(ActorPermissions.current());
		if (!missing.isEmpty()) {
			throw new ForbiddenOperationException(ErrorCode.PRIVILEGE_ESCALATION, "Impossible de " + action
					+ " : vous ne possédez pas " + String.join(", ", missing) + ".");
		}
	}

	/**
	 * Le cache est vidé après validation de la transaction : une requête concurrente ne peut
	 * pas y remettre l'ancienne matrice entre-temps.
	 */
	private void evictCacheAfterCommit() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					evictPermissionCache();
				}
			});
		}
		else {
			evictPermissionCache();
		}
	}

	private RoleResponse toResponse(Role role) {
		boolean admin = ADMIN.equals(role.getCode());
		List<String> permissions = admin ? this.permissionService.allCodes()
				: codes(role.getPermissions()).stream().sorted().toList();
		return new RoleResponse(role.getId(), role.getCode(), role.getLabel(), role.getDescription(), role.isBuiltIn(),
				!admin, permissions, this.repository.countUsers(role.getId()));
	}

	private static Set<String> codes(Collection<Permission> permissions) {
		return permissions.stream().map(Permission::getCode).collect(Collectors.toUnmodifiableSet());
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

}
