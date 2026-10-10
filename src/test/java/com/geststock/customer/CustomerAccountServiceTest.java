package com.geststock.customer;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.geststock.common.jpa.BaseEntity;
import com.geststock.customer.dto.CustomerAccountEntryResponse;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CustomerAccountServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");

	private static final UUID ACTOR = UUID.randomUUID();

	@Mock
	private CustomerService customers;

	@Mock
	private CustomerAccountEntryRepository entries;

	@Mock
	private EntityManager entityManager;

	private CustomerAccountService service;

	private Customer customer;

	@BeforeEach
	void setUp() {
		this.service = new CustomerAccountService(this.customers, this.entries, this.entityManager,
				Clock.fixed(NOW, ZoneOffset.UTC));
		this.customer = customer(new BigDecimal("100000"));
		SecurityContextHolder.getContext()
			.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ACTOR.toString(), null, List.of()));
	}

	@AfterEach
	void clearSecurity() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void aCreditSaleWithinTheLimitIncreasesTheDebtUnderLock() {
		given(this.customers.load(this.customer.getId())).willReturn(this.customer);
		given(this.entries.balanceOf(this.customer.getId())).willReturn(new BigDecimal("60000.00"));
		given(this.entries.save(any(CustomerAccountEntry.class))).willAnswer(invocation -> invocation.getArgument(0));
		UUID sale = UUID.randomUUID();

		CustomerAccountEntryResponse entry = this.service.recordCreditSale(this.customer.getId(), new BigDecimal("40000"),
				sale, "VTE-2026-000001");

		assertThat(entry.type()).isEqualTo(CustomerAccountEntryType.CREDIT_SALE);
		assertThat(entry.direction()).isEqualTo(CustomerAccountEntryType.Direction.DEBIT);
		assertThat(entry.amount()).isEqualByComparingTo("40000");
		assertThat(entry.balanceAfter()).as("plafond atteint exactement : accepté").isEqualByComparingTo("100000");
		assertThat(entry.sourceType()).isEqualTo("SALE");
		assertThat(entry.sourceId()).isEqualTo(sale);
		assertThat(entry.createdByUserId()).isEqualTo(ACTOR);
		assertThat(entry.createdAt()).isEqualTo(NOW);

		InOrder order = inOrder(this.entityManager, this.entries);
		order.verify(this.entityManager).refresh(this.customer, LockModeType.PESSIMISTIC_WRITE);
		order.verify(this.entries).balanceOf(this.customer.getId());
		order.verify(this.entries).save(any(CustomerAccountEntry.class));
	}

	@Test
	void aCreditSaleBeyondTheLimitIsRefused() {
		given(this.customers.load(this.customer.getId())).willReturn(this.customer);
		given(this.entries.balanceOf(this.customer.getId())).willReturn(new BigDecimal("60000.00"));

		assertThatThrownBy(() -> this.service.recordCreditSale(this.customer.getId(), new BigDecimal("40000.01"),
				UUID.randomUUID(), null))
			.isInstanceOfSatisfying(ApiException.class, ex -> {
				assertThat(ex.getCode()).isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED);
				assertThat(ex.getDetails()).containsEntry("available", new BigDecimal("40000.00"));
			});
		verify(this.entries, never()).save(any());
	}

	@Test
	void aCustomerWithoutCreditCannotBuyOnCredit() {
		Customer cashOnly = customer(BigDecimal.ZERO);
		given(this.customers.load(cashOnly.getId())).willReturn(cashOnly);
		given(this.entries.balanceOf(cashOnly.getId())).willReturn(BigDecimal.ZERO);

		assertThatThrownBy(() -> this.service.recordCreditSale(cashOnly.getId(), BigDecimal.ONE, UUID.randomUUID(), null))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED);
	}

	@Test
	void anInactiveCustomerCannotBuyOnCreditButCanStillPay() {
		this.customer.deactivate();
		given(this.customers.load(this.customer.getId())).willReturn(this.customer);

		assertThatThrownBy(() -> this.service.recordCreditSale(this.customer.getId(), BigDecimal.TEN, UUID.randomUUID(),
				null))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CUSTOMER_INACTIVE);

		given(this.entries.balanceOf(this.customer.getId())).willReturn(new BigDecimal("25000.00"));
		given(this.entries.save(any(CustomerAccountEntry.class))).willAnswer(invocation -> invocation.getArgument(0));
		CustomerAccountEntryResponse payment = this.service.recordCredit(this.customer.getId(),
				CustomerAccountEntryType.PAYMENT, new BigDecimal("25000"), UUID.randomUUID(), "REG-1", "Solde");
		assertThat(payment.direction()).isEqualTo(CustomerAccountEntryType.Direction.CREDIT);
		assertThat(payment.sourceType()).isEqualTo("CUSTOMER_PAYMENT");
		assertThat(payment.balanceAfter()).isEqualByComparingTo("0");
	}

	@Test
	void aPaymentCannotExceedTheDebt() {
		given(this.customers.load(this.customer.getId())).willReturn(this.customer);
		given(this.entries.balanceOf(this.customer.getId())).willReturn(new BigDecimal("15000.00"));

		assertThatThrownBy(() -> this.service.recordCredit(this.customer.getId(), CustomerAccountEntryType.PAYMENT,
				new BigDecimal("15000.50"), UUID.randomUUID(), null, null))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.AMOUNT_EXCEEDS_CUSTOMER_BALANCE);
		verify(this.entries, never()).save(any());
	}

	@Test
	void aDocumentIsPostedOnlyOnce() {
		UUID sale = UUID.randomUUID();
		given(this.customers.load(this.customer.getId())).willReturn(this.customer);
		given(this.entries.existsBySource("SALE", sale, CustomerAccountEntryType.CREDIT_SALE)).willReturn(true);

		assertThatThrownBy(() -> this.service.recordCreditSale(this.customer.getId(), BigDecimal.TEN, sale, null))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.CUSTOMER_ACCOUNT_ENTRY_ALREADY_RECORDED);
		verify(this.entries, never()).save(any());
	}

	@Test
	void amountsMustBePositiveWithTwoDecimalsAtMost() {
		for (String amount : new String[] { "0", "-10", "10.005" }) {
			assertThatThrownBy(() -> this.service.recordCreditSale(this.customer.getId(), new BigDecimal(amount),
					UUID.randomUUID(), null))
				.extracting(ex -> ((ApiException) ex).getCode())
				.isEqualTo(ErrorCode.INVALID_PARAMETER);
		}
		verifyNoInteractions(this.customers, this.entries);
	}

	@Test
	void onlyCreditEntriesGoThroughRecordCredit() {
		assertThatThrownBy(() -> this.service.recordCredit(this.customer.getId(), CustomerAccountEntryType.CREDIT_SALE,
				BigDecimal.TEN, UUID.randomUUID(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anAuthenticatedUserIsMandatory() {
		SecurityContextHolder.clearContext();

		assertThatThrownBy(() -> this.service.recordCreditSale(this.customer.getId(), BigDecimal.TEN, UUID.randomUUID(),
				null))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.UNAUTHENTICATED);
		verify(this.entries, never()).existsBySource(anyString(), any(), any());
	}

	@Test
	void theCreditStatusExposesTheAvailableCredit() {
		given(this.customers.load(this.customer.getId())).willReturn(this.customer);
		given(this.entries.balanceOf(this.customer.getId())).willReturn(new BigDecimal("30000.00"));

		var status = this.service.creditStatus(this.customer.getId());

		assertThat(status.creditLimit()).isEqualByComparingTo("100000");
		assertThat(status.balance()).isEqualByComparingTo("30000");
		assertThat(status.availableCredit()).isEqualByComparingTo("70000");
		assertThat(status.paymentTermsDays()).isEqualTo(30);
	}

	private static Customer customer(BigDecimal creditLimit) {
		Customer customer = new Customer("CLI-000001", CustomerType.INDIVIDUAL, "Mariam Bamba");
		customer.changeTerms(PriceCategory.RETAIL, creditLimit, 30);
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

}
