package com.geststock.warehouse;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import org.springframework.test.util.ReflectionTestUtils;

import com.geststock.common.security.ActorWarehouseScope;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/** Règles de résolution du dépôt courant et de périmètre (sans base de données). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WarehouseAccessTest {

	@Mock
	private WarehouseRepository repository;

	private WarehouseAccess access;

	private Warehouse main;

	private Warehouse store;

	private Warehouse depot;

	private Warehouse closed;

	@BeforeEach
	void setUp() {
		this.main = warehouse("MAG-01", "Magasin principal", true);
		this.main.markMain();
		this.store = warehouse("MAG-02", "Magasin Plateau", true);
		this.depot = warehouse("DEP-01", "Dépôt secondaire", true);
		this.closed = warehouse("MAG-09", "Ancien magasin", false);
		Map<UUID, Warehouse> all = Map.of(this.main.getId(), this.main, this.store.getId(), this.store,
				this.depot.getId(), this.depot, this.closed.getId(), this.closed);
		given(this.repository.findById(any())).willAnswer(call -> Optional.ofNullable(all.get(call.getArgument(0))));
		given(this.repository.findByMainTrue()).willReturn(Optional.of(this.main));
		given(this.repository.findAllByIdInAndActiveTrue(any())).willAnswer(call -> {
			Collection<UUID> ids = call.getArgument(0);
			return ids.stream().map(all::get).filter(w -> w != null && w.isActive()).toList();
		});
		this.access = new WarehouseAccess(this.repository);
	}

	@Test
	void requestedWarehouseIsUsedWhenAccessible() {
		ActorWarehouseScope scope = assigned(this.store.getId(), this.store.getId(), this.depot.getId());

		assertThat(this.access.resolve(this.depot.getId().toString(), scope).id()).isEqualTo(this.depot.getId());
	}

	@Test
	void inaccessibleActiveWarehouseIsRefusedNeverSilentlyReplaced() {
		ActorWarehouseScope scope = assigned(this.store.getId(), this.store.getId());

		assertThatThrownBy(() -> this.access.resolve(this.depot.getId().toString(), scope))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.WAREHOUSE_ACCESS_DENIED));
	}

	@Test
	void missingHeaderUsesTheDefaultWarehouse() {
		ActorWarehouseScope scope = assigned(this.store.getId(), this.store.getId(), this.depot.getId());

		assertThat(this.access.resolve(null, scope).id()).isEqualTo(this.store.getId());
		assertThat(this.access.resolve("  ", scope).id()).isEqualTo(this.store.getId());
	}

	@Test
	void malformedIdentifierIsRefusedNeverReplaced() {
		ActorWarehouseScope scope = assigned(this.store.getId(), this.store.getId(), this.depot.getId());

		assertRefused(() -> this.access.resolve("wh_1", scope), ErrorCode.INVALID_WAREHOUSE_ID);
		assertRefused(() -> this.access.resolve("not-a-uuid", scope), ErrorCode.INVALID_WAREHOUSE_ID);
	}

	@Test
	void unknownWarehouseIsRefusedNeverReplaced() {
		ActorWarehouseScope scope = assigned(this.store.getId(), this.store.getId(), this.depot.getId());

		assertRefused(() -> this.access.resolve(UUID.randomUUID().toString(), scope), ErrorCode.UNKNOWN_WAREHOUSE);
	}

	@Test
	void deactivatedWarehouseIsRefusedNeverReplaced() {
		ActorWarehouseScope scope = assigned(this.store.getId(), this.store.getId(), this.closed.getId());

		assertRefused(() -> this.access.resolve(this.closed.getId().toString(), scope), ErrorCode.WAREHOUSE_INACTIVE);
	}

	@Test
	void deactivatedWarehouseOutsideThePerimeterIsAnAccessRefusal() {
		ActorWarehouseScope scope = assigned(this.store.getId(), this.store.getId());

		assertRefused(() -> this.access.resolve(this.closed.getId().toString(), scope),
				ErrorCode.WAREHOUSE_ACCESS_DENIED);
	}

	@Test
	void withoutDefaultTheFirstAssignedActiveWarehouseIsUsed() {
		ActorWarehouseScope scope = assigned(null, this.store.getId(), this.depot.getId());

		assertThat(this.access.resolve(null, scope).id()).as("ordre alphabétique : Dépôt < Magasin")
			.isEqualTo(this.depot.getId());
	}

	@Test
	void unassignedUserWorksInTheMainWarehouseOnly() {
		ActorWarehouseScope scope = new ActorWarehouseScope(false, null, Set.of());

		assertThat(this.access.resolve(null, scope).id()).isEqualTo(this.main.getId());
		assertThat(this.access.resolve(this.main.getId().toString(), scope).id()).isEqualTo(this.main.getId());
		assertThat(this.access.accessibleIds(scope)).containsExactly(this.main.getId());
		// L'exception « sans affectation » ne vaut que sans en-tête : un dépôt désigné est contrôlé.
		assertRefused(() -> this.access.resolve(this.store.getId().toString(), scope),
				ErrorCode.WAREHOUSE_ACCESS_DENIED);
		assertRefused(() -> this.access.resolve("wh_1", scope), ErrorCode.INVALID_WAREHOUSE_ID);
	}

	@Test
	void warehouseAllGivesAccessToEveryActiveWarehouse() {
		ActorWarehouseScope scope = new ActorWarehouseScope(true, null, Set.of(this.store.getId()));

		assertThat(this.access.accessibleIds(scope)).isNull();
		assertThat(this.access.resolve(this.depot.getId().toString(), scope).id()).isEqualTo(this.depot.getId());
		assertThat(this.access.resolve(null, scope).id()).as("premier dépôt attribué").isEqualTo(this.store.getId());
		assertThat(this.access.resolve(null, new ActorWarehouseScope(true, null, Set.of())).id())
			.isEqualTo(this.main.getId());
	}

	@Test
	void userWhoseWarehousesAreAllClosedHasNoWarehouse() {
		ActorWarehouseScope scope = assigned(this.closed.getId(), this.closed.getId());

		assertThatThrownBy(() -> this.access.resolve(this.main.getId().toString(), scope))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.WAREHOUSE_ACCESS_DENIED));
		assertThatThrownBy(() -> this.access.resolve(null, scope))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.NO_WAREHOUSE_AVAILABLE));
	}

	private static void assertRefused(ThrowingCallable call, ErrorCode code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.getCode()).isEqualTo(code));
	}

	private static ActorWarehouseScope assigned(UUID defaultId, UUID... ids) {
		return new ActorWarehouseScope(false, defaultId, Set.copyOf(List.of(ids)));
	}

	private static Warehouse warehouse(String code, String name, boolean active) {
		Warehouse warehouse = new Warehouse(code, name, WarehouseType.STORE);
		ReflectionTestUtils.setField(warehouse, "id", UUID.randomUUID());
		if (!active) {
			warehouse.deactivate();
		}
		return warehouse;
	}

}
