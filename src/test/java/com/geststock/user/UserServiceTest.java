package com.geststock.user;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.geststock.audit.AuditService;
import com.geststock.common.jpa.BaseEntity;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.role.Role;
import com.geststock.role.RoleService;
import com.geststock.user.dto.CreateUserRequest;
import com.geststock.user.dto.ResetPasswordRequest;
import com.geststock.user.dto.UpdateUserRequest;
import com.geststock.user.dto.UserResponse;
import com.geststock.warehouse.WarehouseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Règles du service utilisateur, sans base de données. */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	private static final String RAW_PASSWORD = "Optique2026";

	private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

	@Mock
	private AppUserRepository repository;

	@Mock
	private RoleService roleService;

	@Mock
	private WarehouseService warehouseService;

	@Mock
	private PasswordEncoder passwordEncoder;

	@Mock
	private ApplicationEventPublisher events;

	private UserService service;

	@BeforeEach
	void setUp() {
		this.service = new UserService(this.repository, this.roleService, this.warehouseService, this.passwordEncoder,
				new PasswordPolicy(), new UserMapper(), this.events, org.mockito.Mockito.mock(AuditService.class),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void createStoresOnlyTheHashAndNeverReturnsIt() {
		given(this.roleService.findAllByCodes(List.of("CAISSIER"))).willReturn(Set.of(new Role("CAISSIER", "Caissier")));
		given(this.passwordEncoder.encode(RAW_PASSWORD)).willReturn("{bcrypt}$2a$12$hash");
		given(this.repository.saveAndFlush(any(AppUser.class))).willAnswer(invocation -> invocation.getArgument(0));

		UserResponse response = this.service.create(request("pierre.d", RAW_PASSWORD));

		ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
		verify(this.repository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}$2a$12$hash").doesNotContain(RAW_PASSWORD);
		assertThat(saved.getValue().isMustChangePassword()).as("mot de passe initial temporaire").isTrue();
		assertThat(response.hasPassword()).isTrue();
		assertThat(response.toString()).doesNotContain(RAW_PASSWORD).doesNotContain("$2a$");
	}

	@Test
	void createSplitsLegacyFullNameAndMapsLegacyRole() {
		given(this.roleService.findAllByCodes(List.of("CAISSIER"))).willReturn(Set.of(new Role("CAISSIER", "Caissier")));
		given(this.repository.saveAndFlush(any(AppUser.class))).willAnswer(invocation -> invocation.getArgument(0));

		UserResponse response = this.service.create(request("pierre.d", null));

		assertThat(response.firstName()).isEqualTo("Pierre");
		assertThat(response.lastName()).isEqualTo("Diallo");
		assertThat(response.fullName()).isEqualTo("Pierre Diallo");
		assertThat(response.role()).isEqualTo("EMPLOYEE");
		assertThat(response.hasPassword()).isFalse();
		verify(this.passwordEncoder, never()).encode(anyString());
	}

	@Test
	void createRejectsDuplicateUsernameIgnoringCase() {
		given(this.repository.existsByUsernameIgnoringCase("Pierre.D")).willReturn(true);

		assertThatThrownBy(() -> this.service.create(request("Pierre.D", null)))
			.isInstanceOf(ApiException.class)
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.USERNAME_ALREADY_USED);
		verify(this.repository, never()).saveAndFlush(any());
	}

	@Test
	void createRejectsWeakPasswordBeforeHashing() {
		given(this.roleService.findAllByCodes(List.of("CAISSIER"))).willReturn(Set.of(new Role("CAISSIER", "Caissier")));

		assertThatThrownBy(() -> this.service.create(request("pierre.d", "faible")))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PASSWORD_POLICY_VIOLATION);
		verify(this.passwordEncoder, never()).encode(anyString());
	}

	@Test
	void cannotDeactivateTheLastActiveAdministrator() {
		AppUser admin = adminUser();
		given(this.repository.findById(admin.getId())).willReturn(Optional.of(admin));
		given(this.repository.countActiveWithRoleExcluding(RoleService.ADMIN, admin.getId())).willReturn(0L);

		assertThatThrownBy(() -> this.service.setActive(admin.getId(), false))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.LAST_ADMIN_REQUIRED);
		assertThat(admin.isActive()).isTrue();
	}

	@Test
	void deleteDeactivatesInsteadOfRemoving() {
		AppUser admin = adminUser();
		given(this.repository.findById(admin.getId())).willReturn(Optional.of(admin));
		given(this.repository.countActiveWithRoleExcluding(RoleService.ADMIN, admin.getId())).willReturn(1L);
		given(this.repository.saveAndFlush(admin)).willReturn(admin);

		this.service.delete(admin.getId());

		assertThat(admin.isActive()).isFalse();
		verify(this.events).publishEvent(new com.geststock.user.event.UserDeactivatedEvent(admin.getId()));
		verify(this.repository, never()).delete(any(AppUser.class));
		verify(this.repository, never()).deleteById(any(UUID.class));
	}

	@Test
	void cannotRemoveAdminRoleFromTheLastActiveAdministrator() {
		AppUser admin = adminUser();
		given(this.repository.findById(admin.getId())).willReturn(Optional.of(admin));
		given(this.roleService.findAllByCodes(List.of("CAISSIER"))).willReturn(Set.of(new Role("CAISSIER", "Caissier")));
		given(this.repository.countActiveWithRoleExcluding(RoleService.ADMIN, admin.getId())).willReturn(0L);

		UpdateUserRequest update = new UpdateUserRequest("admin", null, "Konaté", null, null, null, List.of("CAISSIER"),
				null, null);

		assertThatThrownBy(() -> this.service.update(admin.getId(), update))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.LAST_ADMIN_REQUIRED);
	}

	@Test
	void updateKeepsEmailAndPhoneWhenTheyAreNotSent() {
		AppUser user = adminUser();
		user.changeContact("admin@optivision.ci", "+225 07 00 00 01");
		given(this.repository.findById(user.getId())).willReturn(Optional.of(user));
		given(this.roleService.findAllByCodes(List.of("ADMIN"))).willReturn(Set.of(new Role("ADMIN", "Administrateur")));
		given(this.repository.existsByEmailIgnoringCaseAndIdNot(eq("admin@optivision.ci"), any())).willReturn(false);
		given(this.repository.saveAndFlush(user)).willReturn(user);

		this.service.update(user.getId(),
				new UpdateUserRequest("admin", null, null, "Moussa Konaté", null, null, List.of("ADMIN"), null, null));

		assertThat(user.getEmail()).isEqualTo("admin@optivision.ci");
		assertThat(user.getPhone()).isEqualTo("+225 07 00 00 01");
		assertThat(user.getFullName()).isEqualTo("Moussa Konaté");
	}

	@Test
	void resetPasswordIsTemporaryByDefault() {
		AppUser user = adminUser();
		given(this.repository.findById(user.getId())).willReturn(Optional.of(user));
		given(this.passwordEncoder.encode(RAW_PASSWORD)).willReturn("{bcrypt}$2a$12$other");

		this.service.resetPassword(user.getId(), new ResetPasswordRequest(RAW_PASSWORD, null));

		assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}$2a$12$other");
		assertThat(user.isMustChangePassword()).isTrue();
		assertThat(user.getCredentialsChangedAt()).as("jetons antérieurs invalidés").isEqualTo(NOW);
		verify(this.events).publishEvent(new com.geststock.user.event.UserCredentialsChangedEvent(user.getId()));
	}

	@Test
	void unknownUserGives404Code() {
		UUID id = UUID.randomUUID();
		given(this.repository.findById(id)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.get(id))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	private static CreateUserRequest request(String username, String password) {
		return new CreateUserRequest(username, null, null, "Pierre Diallo", null, null, password, List.of("CAISSIER"),
				null, null, null);
	}

	private static AppUser adminUser() {
		AppUser admin = new AppUser("admin", "Moussa", "Konaté");
		admin.assignRole(new Role(RoleService.ADMIN, "Administrateur"));
		setId(admin, UUID.randomUUID());
		return admin;
	}

	/** Simule une entité déjà persistée (identifiant attribué par Hibernate en vrai). */
	private static void setId(BaseEntity entity, UUID id) {
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
