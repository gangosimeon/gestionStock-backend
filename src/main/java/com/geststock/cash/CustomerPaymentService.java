package com.geststock.cash;

import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.audit.AuditService;
import com.geststock.cash.dto.CustomerPaymentRequest;
import com.geststock.cash.dto.CustomerPaymentResponse;
import com.geststock.common.api.PageQuery;
import com.geststock.customer.CustomerAccountEntryType;
import com.geststock.customer.CustomerAccountService;
import com.geststock.customer.CustomerService;
import com.geststock.customer.CustomerSnapshot;

/**
 * Règlements de dettes clients (contrat front {@code /api/customers/{id}/payments}).
 *
 * <p>Un règlement, dans <b>une transaction</b> : dette diminuée au compte du client (refus si
 * le montant dépasse la dette : plus de trop-perçu), puis encaissement dans la caisse ouverte
 * de l'utilisateur. Verrous dans l'ordre client puis caisse, comme pour une vente. La base
 * vérifie au COMMIT que l'un ne va jamais sans l'autre.
 */
@Service
@Transactional(readOnly = true)
public class CustomerPaymentService {

	private final CashService cash;

	private final CashEntryRepository entries;

	private final CustomerService customers;

	private final CustomerAccountService accounts;

	private final AuditService audit;

	CustomerPaymentService(CashService cash, CashEntryRepository entries, CustomerService customers,
			CustomerAccountService accounts, AuditService audit) {
		this.cash = cash;
		this.entries = entries;
		this.customers = customers;
		this.accounts = accounts;
		this.audit = audit;
	}

	/** Règlements d'un client, des plus récents aux plus anciens. */
	public Page<CustomerPaymentResponse> list(UUID customerId, PageQuery query) {
		this.customers.getForAssignment(customerId, false);
		return this.entries.findCustomerPayments(customerId, query.toPageable(CashService.ENTRY_SORTS))
			.map(CustomerPaymentService::toResponse);
	}

	@Transactional
	public CustomerPaymentResponse pay(UUID customerId, CustomerPaymentRequest request) {
		CustomerSnapshot customer = this.customers.getForAssignment(customerId, false);
		this.cash.requireOpenSession(null);
		PaymentMethod method = request.paymentMethod() != null ? request.paymentMethod() : PaymentMethod.CASH;
		String note = StringUtils.hasText(request.note()) ? request.note().trim() : null;
		UUID paymentId = UUID.randomUUID();
		String reference = this.cash.nextEntryNumber();

		this.accounts.recordCredit(customer.id(), CustomerAccountEntryType.PAYMENT, request.amount(), paymentId, reference,
				note);
		CashEntry entry = this.cash.post(CashEntryType.CUSTOMER_PAYMENT, method, request.amount(), null, customer.id(),
				CustomerAccountEntryType.PAYMENT.sourceType(), paymentId, customer.code(), note, reference, null);

		CustomerPaymentResponse response = toResponse(entry);
		this.audit.success("PAY", "CUSTOMER", customer.id().toString(), customer.name(),
				"Règlement " + reference + " de " + customer.name() + " : " + entry.getAmount().toPlainString() + " ("
						+ method + ")",
				response, null);
		return response;
	}

	private static CustomerPaymentResponse toResponse(CashEntry entry) {
		return new CustomerPaymentResponse(entry.getId(), entry.getCustomerId(),
				entry.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), entry.getAmount(), entry.getPaymentMethod(),
				entry.getNumber(), entry.getSessionId(), entry.getNote(), entry.getCreatedAt(), entry.getCreatedBy());
	}

}
