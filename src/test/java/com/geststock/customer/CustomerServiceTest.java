package com.geststock.customer;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.geststock.common.jpa.BaseEntity;
import com.geststock.common.security.Permissions;
import com.geststock.customer.dto.CustomerRequest;
import com.geststock.customer.dto.CustomerResponse;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

	@Mock
	private CustomerRepository repository;

	@Mock
	private CustomerAccountEntryRepository entries;

	private CustomerService service;

	@BeforeEach
	void setUp() {
		this.service = new CustomerService(this.repository, this.entries);
	}

	@AfterEach
	void clearSecurity() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void theFrontPayloadCreatesACashCustomerWithDefaults() {
		actingWith(Permissions.CUSTOMER_CREATE);
		given(this.repository.nextCodeNumber()).willReturn(1L, 2L);
		given(this.repository.existsByCode("CLI-000001", null)).willReturn(true);
		given(this.repository.saveAndFlush(any(Customer.class))).willAnswer(invocation -> invocation.getArgument(0));

		CustomerResponse created = this.service.create(frontRequest("  Aminata Traoré ", BigDecimal.ZERO));

		assertThat(created.code()).as("saute un code saisi à la main").isEqualTo("CLI-000002");
		assertThat(created.name()).isEqualTo("Aminata Traoré");
		assertThat(created.type()).isEqualTo(CustomerType.INDIVIDUAL);
		assertThat(created.priceCategory()).isEqualTo(PriceCategory.RETAIL);
		assertThat(created.creditLimit()).isEqualByComparingTo("0");
		assertThat(created.balance()).isEqualByComparingTo("0");
		assertThat(created.availableCredit()).isEqualByComparingTo("0");
		assertThat(created.active()).isTrue();
	}

	@Test
	void grantingCreditAtCreationRequiresTheCreditPermission() {
		actingWith(Permissions.CUSTOMER_CREATE);

		assertThatThrownBy(() -> this.service.create(frontRequest("Clinique", new BigDecimal("500000"))))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CREDIT_LIMIT_CHANGE_FORBIDDEN);
		verify(this.repository, never()).saveAndFlush(any());

		actingWith(Permissions.CUSTOMER_CREATE, Permissions.CUSTOMER_CREDIT);
		given(this.repository.saveAndFlush(any(Customer.class))).willAnswer(invocation -> invocation.getArgument(0));
		CustomerResponse created = this.service.create(new CustomerRequest("cli-vision", CustomerType.COMPANY, "Clinique",
				null, null, null, null, "CI-99", PriceCategory.WHOLESALE, new BigDecimal("500000"), 45, null));
		assertThat(created.code()).isEqualTo("CLI-VISION");
		assertThat(created.creditLimit()).isEqualByComparingTo("500000");
		assertThat(created.availableCredit()).isEqualByComparingTo("500000");
		assertThat(created.paymentTermsDays()).isEqualTo(45);
		verify(this.repository, never()).nextCodeNumber();
	}

	@Test
	void aCashierCanEditACustomerButNotHisCreditLimit() {
		actingWith(Permissions.CUSTOMER_UPDATE);
		Customer customer = existing(new BigDecimal("50000"));
		given(this.repository.findById(customer.getId())).willReturn(Optional.of(customer));
		given(this.repository.saveAndFlush(customer)).willReturn(customer);
		given(this.entries.balanceOf(customer.getId())).willReturn(BigDecimal.ZERO);

		// Le drawer du front renvoie toujours le plafond : inchangé, il est accepté.
		CustomerResponse updated = this.service.update(customer.getId(),
				frontRequest("Kouadio Jean-Pierre", new BigDecimal("50000.00")));
		assertThat(updated.name()).isEqualTo("Kouadio Jean-Pierre");

		assertThatThrownBy(() -> this.service.update(customer.getId(),
				frontRequest("Kouadio Jean-Pierre", new BigDecimal("900000"))))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CREDIT_LIMIT_CHANGE_FORBIDDEN);
		assertThat(customer.getCreditLimit()).isEqualByComparingTo("50000");
	}

	@Test
	void theFrontPayloadKeepsTheFieldsItDoesNotKnow() {
		actingWith(Permissions.CUSTOMER_UPDATE);
		Customer customer = existing(new BigDecimal("50000"));
		given(this.repository.findById(customer.getId())).willReturn(Optional.of(customer));
		given(this.repository.saveAndFlush(customer)).willReturn(customer);
		given(this.entries.balanceOf(customer.getId())).willReturn(BigDecimal.ZERO);

		CustomerResponse updated = this.service.update(customer.getId(),
				new CustomerRequest(null, null, "Kouadio J.-P.", null, null, null, null, null, null, null, null, null));

		assertThat(updated.code()).isEqualTo("CLI-000007");
		assertThat(updated.type()).isEqualTo(CustomerType.COMPANY);
		assertThat(updated.address()).isEqualTo("Plateau");
		assertThat(updated.city()).isEqualTo("Abidjan");
		assertThat(updated.taxId()).isEqualTo("CI-123");
		assertThat(updated.priceCategory()).isEqualTo(PriceCategory.WHOLESALE);
		assertThat(updated.creditLimit()).as("absent = conservé").isEqualByComparingTo("50000");
		assertThat(updated.paymentTermsDays()).isEqualTo(30);
		assertThat(updated.note()).isEqualTo("Client fidèle");
		assertThat(updated.phone()).as("champ du front : absent = effacé").isNull();
		assertThat(updated.email()).isNull();

		CustomerResponse cleared = this.service.update(customer.getId(),
				new CustomerRequest("", null, "Kouadio", null, null, "", "", "", null, null, null, ""));
		assertThat(cleared.code()).as("le code ne s'efface pas").isEqualTo("CLI-000007");
		assertThat(cleared.address()).isNull();
		assertThat(cleared.city()).isNull();
		assertThat(cleared.taxId()).isNull();
		assertThat(cleared.note()).isNull();
	}

	@Test
	void aDuplicateCodeIsRefused() {
		actingWith(Permissions.CUSTOMER_CREATE);
		given(this.repository.existsByCode("CLI-X", null)).willReturn(true);

		assertThatThrownBy(() -> this.service.create(new CustomerRequest("cli-x", null, "Doublon", null, null, null, null,
				null, null, null, null, null)))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CUSTOMER_CODE_ALREADY_USED);
		verify(this.repository, never()).saveAndFlush(any());
	}

	@Test
	void availableCreditNeverGoesBelowZero() {
		Customer customer = existing(new BigDecimal("100000"));
		given(this.repository.findById(customer.getId())).willReturn(Optional.of(customer));
		given(this.entries.balanceOf(customer.getId())).willReturn(new BigDecimal("120000.00"));

		CustomerResponse response = this.service.get(customer.getId());

		assertThat(response.balance()).isEqualByComparingTo("120000");
		assertThat(response.availableCredit()).isEqualByComparingTo("0");
	}

	@Test
	void anInactiveCustomerCannotBeAssignedToANewSale() {
		Customer customer = existing(BigDecimal.ZERO);
		customer.deactivate();
		given(this.repository.findById(customer.getId())).willReturn(Optional.of(customer));

		assertThatThrownBy(() -> this.service.getForAssignment(customer.getId(), true))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CUSTOMER_INACTIVE);
		CustomerSnapshot snapshot = this.service.getForAssignment(customer.getId(), false);
		assertThat(snapshot.priceCategory()).isEqualTo(PriceCategory.WHOLESALE);
		assertThat(snapshot.active()).isFalse();
	}

	@Test
	void unknownCustomerIsNotFound() {
		UUID id = UUID.randomUUID();
		given(this.repository.findById(id)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.get(id))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CUSTOMER_NOT_FOUND);
	}

	private static CustomerRequest frontRequest(String name, BigDecimal creditLimit) {
		return new CustomerRequest(null, null, name, "+225 05 10 20 30", "client@mail.ci", null, null, null, null,
				creditLimit, null, null);
	}

	private static Customer existing(BigDecimal creditLimit) {
		Customer customer = new Customer("CLI-000007", CustomerType.COMPANY, "Kouadio");
		customer.identify("CLI-000007", CustomerType.COMPANY, "Kouadio", "CI-123");
		customer.changeContact("+225 05 55 44 33", "kj@mail.ci", "Plateau", "Abidjan");
		customer.changeTerms(PriceCategory.WHOLESALE, creditLimit, 30);
		customer.annotate("Client fidèle");
		try {
			Field field = BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(customer, UUID.randomUUID());
			return customer;
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void actingWith(String... permissions) {
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(UUID.randomUUID().toString(), null,
					Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList()));
	}

}
