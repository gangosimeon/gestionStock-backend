package com.geststock.user;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.NotFoundException;
import com.geststock.role.Role;
import com.geststock.role.RoleService;
import com.geststock.user.dto.CreateUserRequest;
import com.geststock.user.dto.ResetPasswordRequest;
import com.geststock.user.dto.UpdateUserRequest;
import com.geststock.user.dto.UserFilter;
import com.geststock.user.dto.UserResponse;
import com.geststock.user.event.UserCredentialsChangedEvent;
import com.geststock.user.event.UserDeactivatedEvent;
import com.geststock.warehouse.Warehouse;
import com.geststock.warehouse.WarehouseService;

/**
 * Gestion des comptes utilisateurs (API publique du module {@code user}).
 *
 * <p>Règles :
 * <ul>
 * <li>identifiant et email uniques, sans tenir compte de la casse (RG-USR-1) ;</li>
 * <li>mot de passe vérifié par {@link PasswordPolicy}, stocké uniquement haché ;</li>
 * <li>pas de suppression physique : un compte est désactivé (historique préservé) ;</li>
 * <li>au moins un administrateur actif est toujours conservé.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class UserService {

	static final SortWhitelist SORTS = new SortWhitelist(
			java.util.Map.of(
					"username", "username",
					"lastName", "lastName",
					"firstName", "firstName",
					"fullName", "lastName",
					"email", "email",
					"createdAt", "createdAt",
					"lastLoginAt", "lastLoginAt"),
			Sort.by("lastName", "firstName"));

	private final AppUserRepository repository;

	private final RoleService roleService;

	private final WarehouseService warehouseService;

	private final PasswordEncoder passwordEncoder;

	private final PasswordPolicy passwordPolicy;

	private final UserMapper mapper;

	private final ApplicationEventPublisher events;

	private final Clock clock;

	UserService(AppUserRepository repository, RoleService roleService, WarehouseService warehouseService,
			PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, UserMapper mapper,
			ApplicationEventPublisher events, Clock clock) {
		this.repository = repository;
		this.roleService = roleService;
		this.warehouseService = warehouseService;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.mapper = mapper;
		this.events = events;
		this.clock = clock;
	}

	public Page<UserResponse> search(UserFilter filter, PageQuery query) {
		return this.repository.findAll(UserSpecifications.matching(filter), query.toPageable(SORTS))
			.map(this.mapper::toResponse);
	}

	public UserResponse get(UUID id) {
		return this.mapper.toResponse(load(id));
	}

	@Transactional
	public UserResponse create(CreateUserRequest request) {
		String username = request.username().trim();
		String email = blankToNull(request.email());
		if (this.repository.existsByUsernameIgnoringCase(username)) {
			throw new ConflictException(ErrorCode.USERNAME_ALREADY_USED, ErrorCode.USERNAME_ALREADY_USED.defaultMessage());
		}
		if (email != null && this.repository.existsByEmailIgnoringCase(email)) {
			throw new ConflictException(ErrorCode.EMAIL_ALREADY_USED, ErrorCode.EMAIL_ALREADY_USED.defaultMessage());
		}

		PersonName name = PersonName.of(request.firstName(), request.lastName(), request.fullName());
		AppUser user = new AppUser(username, name.firstName(), name.lastName());
		user.changeContact(email, blankToNull(request.phone()));
		Set<Role> roles = this.roleService.findAllByCodes(request.roles());
		this.roleService.requireGrantable(roles);
		user.replaceRoles(roles);
		applyWarehouses(user, request.defaultWarehouseId(), request.warehouseIds());
		if (StringUtils.hasLength(request.password())) {
			this.passwordPolicy.check(request.password(), username);
			user.changePasswordHash(this.passwordEncoder.encode(request.password()), true, Instant.now(this.clock));
		}
		if (Boolean.FALSE.equals(request.isActive())) {
			user.deactivate();
		}
		return this.mapper.toResponse(this.repository.saveAndFlush(user));
	}

	@Transactional
	public UserResponse update(UUID id, UpdateUserRequest request) {
		AppUser user = load(id);
		String username = request.username().trim();
		if (this.repository.existsByUsernameIgnoringCaseAndIdNot(username, id)) {
			throw new ConflictException(ErrorCode.USERNAME_ALREADY_USED, ErrorCode.USERNAME_ALREADY_USED.defaultMessage());
		}

		Set<Role> roles = this.roleService.findAllByCodes(request.roles());
		checkRoleChange(user, roles);

		PersonName name = PersonName.of(request.firstName(), request.lastName(), request.fullName());
		user.rename(username, name.firstName(), name.lastName());

		// null = inchangé (le front actuel n'envoie ni email ni téléphone vide) ; "" = effacer.
		String email = request.email() != null ? blankToNull(request.email()) : user.getEmail();
		if (email != null && this.repository.existsByEmailIgnoringCaseAndIdNot(email, id)) {
			throw new ConflictException(ErrorCode.EMAIL_ALREADY_USED, ErrorCode.EMAIL_ALREADY_USED.defaultMessage());
		}
		String phone = request.phone() != null ? blankToNull(request.phone()) : user.getPhone();
		user.changeContact(email, phone);
		user.replaceRoles(roles);
		applyWarehouses(user, request.defaultWarehouseId(), request.warehouseIds());
		return this.mapper.toResponse(this.repository.saveAndFlush(user));
	}

	/**
	 * Remplace les rôles d'un utilisateur. Interdit d'accorder un rôle dont on ne détient pas
	 * soi-même toutes les permissions, et de retirer le rôle ADMIN au dernier administrateur.
	 */
	@Transactional
	public UserResponse assignRoles(UUID id, Collection<String> roleCodes) {
		AppUser user = load(id);
		Set<Role> roles = this.roleService.findAllByCodes(roleCodes);
		checkRoleChange(user, roles);
		user.replaceRoles(roles);
		return this.mapper.toResponse(this.repository.saveAndFlush(user));
	}

	@Transactional
	public UserResponse setActive(UUID id, boolean active) {
		AppUser user = load(id);
		if (active) {
			user.activate();
		}
		else if (user.isActive()) {
			if (user.hasRole(RoleService.ADMIN)) {
				requireAnotherActiveAdmin(user);
			}
			user.deactivate();
			this.events.publishEvent(new UserDeactivatedEvent(user.getId()));
		}
		return this.mapper.toResponse(this.repository.saveAndFlush(user));
	}

	/**
	 * « Suppression » demandée par le front : le compte est désactivé, jamais effacé, pour
	 * que l'historique (ventes, mouvements, audit) garde son auteur.
	 */
	@Transactional
	public void delete(UUID id) {
		setActive(id, false);
	}

	@Transactional
	public void resetPassword(UUID id, ResetPasswordRequest request) {
		AppUser user = load(id);
		this.passwordPolicy.check(request.password(), user.getUsername());
		boolean temporary = request.temporary() == null || request.temporary();
		user.changePasswordHash(this.passwordEncoder.encode(request.password()), temporary, Instant.now(this.clock));
		this.repository.saveAndFlush(user);
		this.events.publishEvent(new UserCredentialsChangedEvent(user.getId()));
	}

	// --- Outils -------------------------------------------------------------------------

	private AppUser load(UUID id) {
		return this.repository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, ErrorCode.USER_NOT_FOUND.defaultMessage()));
	}

	/** Contrôles communs à tout changement de rôles : escalade de privilèges, dernier ADMIN. */
	private void checkRoleChange(AppUser user, Set<Role> newRoles) {
		Set<Role> added = new LinkedHashSet<>(newRoles);
		added.removeAll(user.getRoles());
		if (!added.isEmpty()) {
			this.roleService.requireGrantable(added);
		}
		boolean losesAdmin = user.hasRole(RoleService.ADMIN)
				&& newRoles.stream().noneMatch(role -> RoleService.ADMIN.equals(role.getCode()));
		if (losesAdmin && user.isActive()) {
			requireAnotherActiveAdmin(user);
		}
	}

	private void requireAnotherActiveAdmin(AppUser user) {
		if (this.repository.countActiveWithRoleExcluding(RoleService.ADMIN, user.getId()) == 0) {
			throw new ConflictException(ErrorCode.LAST_ADMIN_REQUIRED, ErrorCode.LAST_ADMIN_REQUIRED.defaultMessage());
		}
	}

	/**
	 * Dépôts autorisés et dépôt par défaut. {@code null} = inchangé. Le dépôt par défaut est
	 * toujours ajouté aux dépôts autorisés.
	 */
	private void applyWarehouses(AppUser user, UUID defaultWarehouseId, List<UUID> warehouseIds) {
		if (warehouseIds != null) {
			user.replaceWarehouses(warehouseIds.isEmpty() ? Set.of() : this.warehouseService.findActiveByIds(warehouseIds));
		}
		if (defaultWarehouseId != null) {
			Warehouse defaultWarehouse = this.warehouseService.getActive(defaultWarehouseId);
			user.setDefaultWarehouse(defaultWarehouse);
			if (!user.getWarehouses().contains(defaultWarehouse)) {
				Collection<Warehouse> all = new LinkedHashSet<>(user.getWarehouses());
				all.add(defaultWarehouse);
				user.replaceWarehouses(all);
			}
		}
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
