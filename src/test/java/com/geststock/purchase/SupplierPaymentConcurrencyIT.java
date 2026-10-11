package com.geststock.purchase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.exception.ErrorCode;
import com.geststock.purchase.dto.CreatePurchaseOrderRequest;
import com.geststock.purchase.dto.SupplierPaymentRequest;
import com.geststock.stock.StockFixtures;
import com.geststock.support.BusinessScenario;
import com.geststock.support.PostgresTestConfiguration;

import static com.geststock.support.BusinessScenario.as;
import static com.geststock.support.BusinessScenario.refusal;
import static com.geststock.support.BusinessScenario.runConcurrently;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paiements fournisseurs simultanés, en transactions réelles : la somme payée ne dépasse jamais
 * le dû, quel que soit l'entrelacement des paiements globaux et des paiements de commande.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class SupplierPaymentConcurrencyIT {

	@Autowired
	private SupplierPaymentService payments;

	@Autowired
	private PurchaseOrderService orders;

	@Autowired
	private CashService cash;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private BusinessScenario scenario;

	private UUID actor;

	private UUID store;

	private UUID frame;

	private UUID supplier;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.scenario = new BusinessScenario(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("paiements");
		this.store = this.fixtures.warehouse("paiements");
		this.frame = this.fixtures.product("monture-paiement", 10000);
		String code = "FOU-" + this.store.toString().substring(0, 8).toUpperCase();
		this.supplier = this.jdbc.queryForObject("insert into supplier (code, name) values (?, ?) returning id",
				UUID.class, code, "Fournisseur paiements " + code);
		as(this.actor, () -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("1000000"), null, null), this.store));
	}

	@AfterEach
	void tearDown() {
		this.scenario.purge(List.of(this.store), List.of(), List.of(this.supplier));
		this.fixtures.purge();
	}

	@Test
	void concurrentPaymentsNeverPayMoreThanWhatIsDue() throws Exception {
		UUID first = order(3); // 30 000
		UUID second = order(3); // 30 000
		List<Callable<String>> tasks = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			// 8 × 10 000 demandés pour 60 000 dus (3 ciblés sur first, 5 globaux) : 5 ou 6 passent.
			UUID target = i % 3 == 0 ? first : null;
			PaymentMethod method = i % 2 == 0 ? PaymentMethod.CASH : PaymentMethod.BANK_TRANSFER;
			tasks.add(() -> outcome(() -> as(this.actor, () -> this.payments.pay(this.supplier,
					new SupplierPaymentRequest(new BigDecimal("10000"), null, method, target, null, null)))));
		}

		List<String> outcomes = runConcurrently(tasks);

		assertThat(outcomes).allMatch(o -> o.equals("ok")
				|| o.equals(ErrorCode.SUPPLIER_PAYMENT_EXCEEDS_BALANCE.name())
				|| o.equals(ErrorCode.PURCHASE_ORDER_PAYMENT_EXCEEDS_REMAINING.name()));
		// L'issue dépend de l'ordre des threads : un paiement global passé avant un paiement ciblé
		// impute sur la commande la plus ancienne (first), et le paiement ciblé sur first est alors
		// refusé à juste titre (reste dû dépassé) même si second n'est pas soldée. Si k paiements
		// ciblés réussissent, les globaux en réussissent min(5, 6 - k) : 5 ou 6 succès au total.
		// Invariants vérifiés quel que soit l'ordre : chaque succès est payé, rien au-delà du dû, et un
		// refus global (solde dépassé) n'arrive que lorsque tout est réglé.
		long ok = outcomes.stream().filter("ok"::equals).count();
		BigDecimal paid = this.jdbc.queryForObject("select sum(paid_amount) from purchase_order where supplier_id = ?",
				BigDecimal.class, this.supplier);
		assertThat(ok).as("5 ou 6 succès selon l'ordre").isBetween(5L, 6L);
		assertThat(paid).as("payé = succès × 10 000").isEqualByComparingTo(BigDecimal.valueOf(ok * 10000));
		assertThat(paid).as("jamais plus que le dû").isLessThanOrEqualTo(new BigDecimal("60000"));
		if (outcomes.contains(ErrorCode.SUPPLIER_PAYMENT_EXCEEDS_BALANCE.name())) {
			assertThat(paid).as("refus global : plus rien à payer").isEqualByComparingTo("60000");
		}
		assertThat(this.jdbc.queryForObject("""
				select count(*) from purchase_order o
				where o.supplier_id = ? and o.paid_amount <> (select coalesce(sum(a.amount), 0)
				    from supplier_payment_allocation a where a.purchase_order_id = o.id)""", Integer.class, this.supplier))
			.as("montant payé = somme des imputations")
			.isZero();
		assertThat(this.jdbc.queryForObject("""
				select count(*) from cash_entry c join supplier_payment p on p.id = c.source_id
				where c.entry_type = 'SUPPLIER_PAYMENT' and p.supplier_id = ?""", Integer.class, this.supplier))
			.as("un décaissement par paiement en espèces réussi")
			.isEqualTo(this.jdbc.queryForObject(
					"select count(*) from supplier_payment where supplier_id = ? and payment_method = 'CASH'",
					Integer.class, this.supplier));
	}

	private UUID order(int quantity) {
		return as(this.actor,
				() -> this.orders.create(new CreatePurchaseOrderRequest(this.supplier, null, null, null, null,
						List.of(new CreatePurchaseOrderRequest.Line(this.frame, quantity, new BigDecimal("10000")))),
						this.store).id());
	}

	private static String outcome(Runnable action) {
		ErrorCode code = refusal(action);
		return code == null ? "ok" : code.name();
	}

}
