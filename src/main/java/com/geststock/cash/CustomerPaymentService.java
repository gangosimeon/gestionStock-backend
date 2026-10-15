package com.geststock.cash;

import java.time.ZoneOffset;
import java.util.Set;
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
import com.geststock.warehouse.WarehouseAccess;

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

	private final WarehouseAccess warehouseAccess;

	CustomerPaymentService(CashService cash, CashEntryRepository entries, CustomerService customers,
			CustomerAccountService accounts, AuditService audit, WarehouseAccess warehouseAccess) {
		this.cash = cash;
		this.entries = entries;
		this.customers = customers;
		this.accounts = accounts;
		this.audit = audit;
		this.warehouseAccess = warehouseAccess;
	}

	/**
	 * Règlements d'un client, des plus récents aux plus anciens. Sans {@code warehouse:all} :
	 * seulement ceux encaissés dans les dépôts de l'utilisateur (le reste figure, agrégé, dans la
	 * ligne « Autres dépôts » du compte client).
	 */
	public Page<CustomerPaymentResponse> list(UUID customerId, String text, PageQuery query) {
		this.customers.getForAssignment(customerId, false);
		String pattern = com.geststock.common.api.SearchText.pattern(text);
		Set<UUID> visible = this.warehouseAccess.visibleWarehouseIds();
		if (visible == null) {
			return this.entries.findCustomerPayments(customerId, pattern, query.toPageable(CashService.ENTRY_SORTS))
				.map(CustomerPaymentService::toResponse);
		}
		if (visible.isEmpty()) {
			return Page.empty(query.toPageable(CashService.ENTRY_SORTS));
		}
		return this.entries
			.findCustomerPaymentsInWarehouses(customerId, pattern, visible, query.toPageable(CashService.ENTRY_SORTS))
			.map(CustomerPaymentService::toResponse);
	}

	/**
	 * Règlement encaissé dans la caisse ouverte de l'utilisateur, dans le dépôt courant
	 * ({@code CASH_SESSION_OTHER_WAREHOUSE} si sa caisse est ailleurs).
	 */
	@Transactional
	public CustomerPaymentResponse pay(UUID customerId, CustomerPaymentRequest request, UUID currentWarehouseId) {
		CustomerSnapshot customer = this.customers.getForAssignment(customerId, false);
		this.cash.requireOpenSession(currentWarehouseId);
		PaymentMethod method = request.paymentMethod() != null ? request.paymentMethod() : PaymentMethod.CASH;
		String note = StringUtils.hasText(request.note()) ? request.note().trim() : null;
		UUID paymentId = UUID.randomUUID();
		String reference = this.cash.nextEntryNumber();

		this.accounts.recordCredit(customer.id(), CustomerAccountEntryType.PAYMENT, request.amount(), paymentId, reference,
				note, currentWarehouseId);
		CashEntry entry = this.cash.post(CashEntryType.CUSTOMER_PAYMENT, method, request.amount(), currentWarehouseId,
				customer.id(),
				CustomerAccountEntryType.PAYMENT.sourceType(), paymentId, customer.code(), note, reference, null);

		CustomerPaymentResponse response = toResponse(entry);
		this.audit.success("PAY", "CUSTOMER", customer.id().toString(), customer.name(),
				"Règlement " + reference + " de " + customer.name() + " : " + entry.getAmount().toPlainString() + " ("
						+ method + ")",
				response, currentWarehouseId);
		return response;
	}

	private static CustomerPaymentResponse toResponse(CashEntry entry) {
		return new CustomerPaymentResponse(entry.getId(), entry.getCustomerId(),
				entry.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(), entry.getAmount(), entry.getPaymentMethod(),
				entry.getNumber(), entry.getSessionId(), entry.getNote(), entry.getCreatedAt(), entry.getCreatedBy());
	}

}
