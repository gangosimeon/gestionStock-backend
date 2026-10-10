package com.geststock.supplier;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.geststock.common.jpa.BaseEntity;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.supplier.dto.SupplierRequest;
import com.geststock.supplier.dto.SupplierResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

	@Mock
	private SupplierRepository repository;

	private SupplierService service;

	@BeforeEach
	void setUp() {
		this.service = new SupplierService(this.repository);
	}

	@Test
	void generatesTheCodeFromTheSequenceAndSkipsCodesTakenByHand() {
		given(this.repository.nextCodeNumber()).willReturn(1L, 2L);
		given(this.repository.existsByCode("FOU-0001", null)).willReturn(true);
		given(this.repository.saveAndFlush(any(Supplier.class))).willAnswer(invocation -> invocation.getArgument(0));

		SupplierResponse created = this.service.create(frontRequest("Essilor Distribution CI"));

		assertThat(created.code()).isEqualTo("FOU-0002");
	}

	@Test
	void normalizesAGivenCodeAndAppliesDefaults() {
		given(this.repository.saveAndFlush(any(Supplier.class))).willAnswer(invocation -> invocation.getArgument(0));

		SupplierResponse created = this.service.create(new SupplierRequest(" fou-ess ", "  Essilor  ", " ", null, "",
				null, null, null, null, null));

		assertThat(created.code()).isEqualTo("FOU-ESS");
		assertThat(created.name()).isEqualTo("Essilor");
		assertThat(created.contactName()).isNull();
		assertThat(created.email()).as("chaîne vide enregistrée comme absente").isNull();
		assertThat(created.deliveryLeadTimeDays()).isZero();
		assertThat(created.paymentTermsDays()).isZero();
		assertThat(created.active()).isTrue();
		verify(this.repository, never()).nextCodeNumber();
	}

	@Test
	void rejectsADuplicateNameBeforeTouchingTheDatabase() {
		given(this.repository.existsByNameIgnoringCase(eq("Essilor"), any())).willReturn(true);

		assertThatThrownBy(() -> this.service.create(frontRequest("Essilor")))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SUPPLIER_NAME_ALREADY_USED);
		verify(this.repository, never()).saveAndFlush(any());
	}

	@Test
	void rejectsADuplicateCode() {
		given(this.repository.existsByCode("FOU-ESS", null)).willReturn(true);

		assertThatThrownBy(() -> this.service.create(new SupplierRequest("FOU-ESS", "Essilor", null, null, null, null,
				null, null, null, null)))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SUPPLIER_CODE_ALREADY_USED);
		verify(this.repository, never()).saveAndFlush(any());
	}

	@Test
	void theCurrentFrontPayloadKeepsTheFieldsItDoesNotKnow() {
		Supplier supplier = existing();
		given(this.repository.findById(supplier.getId())).willReturn(Optional.of(supplier));
		given(this.repository.saveAndFlush(supplier)).willReturn(supplier);

		// Charge utile du drawer actuel : ni code, ni interlocuteur, ni NCC, ni délai de paiement, ni note.
		SupplierResponse updated = this.service.update(supplier.getId(),
				new SupplierRequest(null, "Essilor CI", null, null, null, null, null, 6, null, null));

		assertThat(updated.code()).isEqualTo("FOU-0007");
		assertThat(updated.name()).isEqualTo("Essilor CI");
		assertThat(updated.contactName()).isEqualTo("Awa Koné");
		assertThat(updated.taxId()).isEqualTo("CI-123");
		assertThat(updated.paymentTermsDays()).isEqualTo(30);
		assertThat(updated.note()).isEqualTo("Livraison le mardi");
		assertThat(updated.deliveryLeadTimeDays()).isEqualTo(6);
		assertThat(updated.phone()).as("champ du front : absent = effacé").isNull();
		assertThat(updated.email()).isNull();
	}

	@Test
	void anEmptyStringClearsAnExtendedField() {
		Supplier supplier = existing();
		given(this.repository.findById(supplier.getId())).willReturn(Optional.of(supplier));
		given(this.repository.saveAndFlush(supplier)).willReturn(supplier);

		SupplierResponse updated = this.service.update(supplier.getId(),
				new SupplierRequest("", "Essilor", "", null, null, null, " ", null, 0, ""));

		assertThat(updated.code()).as("le code ne s'efface pas").isEqualTo("FOU-0007");
		assertThat(updated.contactName()).isNull();
		assertThat(updated.taxId()).isNull();
		assertThat(updated.note()).isNull();
		assertThat(updated.paymentTermsDays()).isZero();
		assertThat(updated.deliveryLeadTimeDays()).as("absent = conservé").isEqualTo(5);
	}

	@Test
	void renamingToItsOwnNameIsNotADuplicate() {
		Supplier supplier = existing();
		given(this.repository.findById(supplier.getId())).willReturn(Optional.of(supplier));
		given(this.repository.saveAndFlush(supplier)).willReturn(supplier);

		this.service.update(supplier.getId(), frontRequest("ESSILOR"));

		verify(this.repository).existsByNameIgnoringCase("ESSILOR", supplier.getId());
		verify(this.repository).existsByCode("FOU-0007", supplier.getId());
	}

	@Test
	void unknownSupplierIsNotFound() {
		UUID id = UUID.randomUUID();
		given(this.repository.findById(id)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.get(id))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SUPPLIER_NOT_FOUND);
	}

	@Test
	void anInactiveSupplierCannotBeAssignedButRemainsReadable() {
		Supplier supplier = existing();
		supplier.deactivate();
		given(this.repository.findById(supplier.getId())).willReturn(Optional.of(supplier));

		assertThatThrownBy(() -> this.service.getForAssignment(supplier.getId(), true))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.SUPPLIER_INACTIVE);

		SupplierSnapshot snapshot = this.service.getForAssignment(supplier.getId(), false);
		assertThat(snapshot.name()).isEqualTo("Essilor");
		assertThat(snapshot.deliveryLeadTimeDays()).isEqualTo(5);
		assertThat(snapshot.paymentTermsDays()).isEqualTo(30);
		assertThat(snapshot.active()).isFalse();
	}

	@Test
	void deactivationAndReactivation() {
		Supplier supplier = existing();
		given(this.repository.findById(supplier.getId())).willReturn(Optional.of(supplier));
		given(this.repository.saveAndFlush(supplier)).willReturn(supplier);

		assertThat(this.service.setActive(supplier.getId(), false).active()).isFalse();
		assertThat(this.service.setActive(supplier.getId(), true).active()).isTrue();
		verify(this.repository, never()).existsByNameIgnoringCase(anyString(), any());
	}

	private static SupplierRequest frontRequest(String name) {
		return new SupplierRequest(null, name, null, "+225 20 25 00 10", "commandes@essilor.ci",
				"Zone Ind. Yopougon, Abidjan", null, 5, null, null);
	}

	private static Supplier existing() {
		Supplier supplier = new Supplier("FOU-0007", "Essilor");
		supplier.changeContact("Awa Koné", "+225 20 25 00 10", "commandes@essilor.ci", "Abidjan");
		supplier.changeTerms("CI-123", 5, 30);
		supplier.annotate("Livraison le mardi");
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(supplier, UUID.randomUUID());
			return supplier;
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
