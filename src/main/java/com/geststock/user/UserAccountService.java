package com.geststock.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditModule;
import com.geststock.audit.AuditService;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.role.Role;
import com.geststock.role.RoleService;
import com.geststock.user.dto.UserResponse;
import com.geststock.user.event.UserCredentialsChangedEvent;

/**
 * API du module utilisateur réservée à l'authentification (module {@code security}).
 * Les règles de compte (verrouillage, mot de passe) restent ici, propriétaire des données.
 */
@Service
@Transactional(readOnly = true)
public class UserAccountService {

	private final AppUserRepository repository;

	private final RoleService roleService;

	private final PasswordEncoder passwordEncoder;

	private final PasswordPolicy passwordPolicy;

	private final UserMapper mapper;

	private final ApplicationEventPublisher events;

	private final AuditService audit;

	private final Clock clock;

	UserAccountService(AppUserRepository repository, RoleService roleService, PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy, UserMapper mapper, ApplicationEventPublisher events, AuditService audit,
			Clock clock) {
		this.repository = repository;
		this.roleService = roleService;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.mapper = mapper;
		this.events = events;
		this.audit = audit;
		this.clock = clock;
	}

	/** Compte par identifiant de connexion (casse ignorée). */
	public Optional<UserAccount> findByUsername(String username) {
		return this.repository.findWithAccessByUsername(username).map(UserAccountService::toAccount);
	}

	/** Compte par identifiant technique (sujet du JWT). */
	public Optional<UserAccount> findById(UUID id) {
		return this.repository.findById(id).map(UserAccountService::toAccount);
	}

	/** Profil public de l'utilisateur connecté (sans aucune donnée de mot de passe). */
	public UserResponse profile(UUID id) {
		return this.mapper.toResponse(this.repository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, ErrorCode.USER_NOT_FOUND.defaultMessage())));
	}

	/**
	 * Enregistre un échec de connexion dans une transaction indépendante : il est conservé
	 * même si la requête de connexion se termine en erreur.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailedLogin(UUID id, int maxAttempts, Duration lockDuration) {
		this.repository.findById(id).ifPresent(user -> {
			Instant now = Instant.now(this.clock);
			user.recordFailedLogin(maxAttempts, now.plus(lockDuration));
		});
	}

	@Transactional
	public void recordSuccessfulLogin(UUID id) {
		this.repository.findById(id).ifPresent(user -> user.recordSuccessfulLogin(Instant.now(this.clock)));
	}

	/**
	 * Changement de son propre mot de passe : l'ancien doit être fourni. Toutes les sessions
	 * existantes sont fermées.
	 */
	@Transactional
	public void changeOwnPassword(UUID id, String currentPassword, String newPassword) {
		AppUser user = this.repository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, ErrorCode.USER_NOT_FOUND.defaultMessage()));
		if (!user.hasPassword() || !this.passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			throw new InvalidRequestException(ErrorCode.CURRENT_PASSWORD_INVALID,
					ErrorCode.CURRENT_PASSWORD_INVALID.defaultMessage());
		}
		if (this.passwordEncoder.matches(newPassword, user.getPasswordHash())) {
			throw new InvalidRequestException(ErrorCode.PASSWORD_POLICY_VIOLATION,
					"Mot de passe refusé : il doit être différent de l'actuel.");
		}
		this.passwordPolicy.check(newPassword, user.getUsername());
		user.changePasswordHash(this.passwordEncoder.encode(newPassword), false, Instant.now(this.clock));
		this.repository.saveAndFlush(user);
		this.events.publishEvent(new UserCredentialsChangedEvent(id));
		this.audit.record(AuditEvent.of("PASSWORD_CHANGE", "USER", id, user.getUsername())
			.module(AuditModule.AUTH)
			.details("Mot de passe changé par l'utilisateur ; autres sessions fermées"));
	}

	/** Identifiants de connexion des utilisateurs demandés (affichage des historiques). */
	public Map<UUID, String> usernames(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return this.repository.findAllById(ids).stream()
			.collect(Collectors.toUnmodifiableMap(AppUser::getId, AppUser::getUsername));
	}

	/** {@code true} si au moins un administrateur actif existe. */
	public boolean hasActiveAdministrator() {
		return this.repository.countActiveWithRoleExcluding(RoleService.ADMIN, new UUID(0, 0)) > 0;
	}

	/**
	 * Crée l'administrateur initial (premier démarrage). Mot de passe temporaire : il devra
	 * être changé. Refuse un identifiant déjà pris.
	 */
	@Transactional
	public UUID createInitialAdministrator(String username, String lastName, String password) {
		if (this.repository.existsByUsernameIgnoringCase(username)) {
			throw new InvalidRequestException(ErrorCode.USERNAME_ALREADY_USED,
					"Administrateur initial : l'identifiant " + username + " est déjà utilisé.");
		}
		this.passwordPolicy.check(password, username);
		AppUser admin = new AppUser(username, null, lastName);
		admin.replaceRoles(this.roleService.findAllByCodes(java.util.List.of(RoleService.ADMIN)));
		admin.changePasswordHash(this.passwordEncoder.encode(password), true, Instant.now(this.clock));
		UUID id = this.repository.saveAndFlush(admin).getId();
		this.audit.record(AuditEvent.of("CREATE", "USER", id, username)
			.details("Administrateur initial créé au démarrage (mot de passe provisoire)"));
		return id;
	}

	private static UserAccount toAccount(AppUser user) {
		return new UserAccount(user.getId(), user.getUsername(), user.getPasswordHash(), user.isActive(),
				user.isMustChangePassword(), user.getLockedUntil(), user.getCredentialsChangedAt(),
				user.getRoles().stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet()),
				user.getDefaultWarehouse() != null ? user.getDefaultWarehouse().getId() : null,
				user.getWarehouses().stream().map(com.geststock.warehouse.Warehouse::getId)
					.collect(Collectors.toUnmodifiableSet()));
	}

}
