package com.geststock.scenario;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.cash.CashEntryType;
import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.cash.dto.CashOperationRequest;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.exception.ErrorCode;
import com.geststock.returns.CustomerReturnService;
import com.geststock.returns.ItemCondition;
import com.geststock.returns.ReturnSettlement;
import com.geststock.returns.dto.CreateCustomerReturnRequest;
import com.geststock.sale.SaleService;
import com.geststock.sale.dto.CancelSaleRequest;
import com.geststock.sale.dto.CreateSaleRequest;
import com.geststock.sale.dto.SaleResponse;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.BusinessScenario;
import com.geststock.support.PostgresTestConfiguration;

import static com.geststock.support.BusinessScenario.as;
import static com.geststock.support.BusinessScenario.refusal;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Atomicité entre modules : une correction commerciale qui remet le stock en place puis échoue
 * sur la caisse (tiroir qui ne peut pas payer le remboursement) est entièrement annulée — ni
 * remise en stock, ni pièce, ni écriture de caisse, ni trace d'audit.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CrossModuleRollbackIT {

	@Autowired
	private SaleService sales;

	@Autowired
	private CustomerReturnService returns;

	@Autowired
	private StockService stock;

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

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.scenario = new BusinessScenario(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("rollback");
		this.store = this.fixtures.warehouse("magasin");
		this.frame = this.fixtures.product("monture", 15000); // vente 30 000
		as(this.actor, () -> this.stock.applyManualMovement(StockMovementCommand.of(this.store, this.frame,
				StockMovementType.RECEIPT, 5, StockSourceType.MANUAL, "Stock initial")));
		// Tiroir vide à l'ouverture.
		as(this.actor, () -> this.cash.open(new OpenCashSessionRequest(BigDecimal.ZERO, null, null), this.store));
	}

	@AfterEach
	void tearDown() {
		assertThat(this.fixtures.invariantViolations()).isEmpty();
		this.scenario.purge(List.of(this.store), List.of(), List.of());
		this.fixtures.purge();
	}

	@Test
	void aCashRefundTheDrawerCannotPayRollsBackTheReturnAndItsRestock() {
		// Vente payée par Mobile Money : rien n'entre dans le tiroir.
		SaleResponse sale = sell(PaymentMethod.MOBILE_MONEY);
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(4);
		long movements = this.fixtures.movementCount(this.store, this.frame);

		// Remboursement demandé en espèces : le retour et la remise en stock sont écrits, puis la
		// sortie de caisse (30 000) dépasse les espèces du tiroir (0).
		ErrorCode refused = refusal(() -> as(this.actor, () -> this.returns.create(sale.id(),
				new CreateCustomerReturnRequest(ReturnSettlement.REFUND, PaymentMethod.CASH, "Rendu",
						List.of(new CreateCustomerReturnRequest.Item(this.frame, 1, ItemCondition.RESELLABLE))))));

		assertThat(refused).isEqualTo(ErrorCode.CASH_INSUFFICIENT);
		assertThat(this.fixtures.level(this.store, this.frame)).as("remise en stock annulée").isEqualTo(4);
		assertThat(this.fixtures.movementCount(this.store, this.frame)).isEqualTo(movements);
		assertThat(count("select count(*) from customer_return where sale_id = ?", sale.id())).isZero();
		assertThat(count("select count(*) from cash_entry where entry_type = 'RETURN_REFUND' and session_id in "
				+ "(select id from cash_session where warehouse_id = ?)", this.store)).isZero();
		assertThat(count("select count(*) from audit_log where entity_type = 'CUSTOMER_RETURN' and warehouse_id = ? "
				+ "and status = 'SUCCESS'", this.store)).isZero();

		// Remboursé par le même moyen que la vente, le retour passe.
		as(this.actor, () -> this.returns.create(sale.id(), new CreateCustomerReturnRequest(ReturnSettlement.REFUND,
				null, "Rendu", List.of(new CreateCustomerReturnRequest.Item(this.frame, 1, ItemCondition.RESELLABLE)))));
		assertThat(this.fixtures.level(this.store, this.frame)).isEqualTo(5);
		assertThat(this.jdbc.queryForObject("""
				select payment_method from cash_entry where entry_type = 'RETURN_REFUND' and session_id in
				    (select id from cash_session where warehouse_id = ?)""", String.class, this.store))
			.as("moyen de remboursement par défaut : celui de la vente").isEqualTo("MOBILE_MONEY");
	}

	@Test
	void aCancellationWhoseRefundTheDrawerCannotPayKeepsTheSaleAndItsStockExit() {
		SaleResponse sale = sell(PaymentMethod.CASH);
		// Les espèces de la vente partent à la banque : le tiroir ne peut plus rembourser.
		as(this.actor, () -> this.cash.recordManual(new CashOperationRequest(CashEntryType.Direction.OUT,
				new BigDecimal("30000"), "Dépôt en banque")));

		ErrorCode refused = refusal(() -> as(this.actor, () -> this.sales.cancel(sale.id(),
				new CancelSaleRequest("Erreur"))));

		assertThat(refused).isEqualTo(ErrorCode.CASH_INSUFFICIENT);
		assertThat(this.jdbc.queryForObject("select status from sale where id = ?", String.class, sale.id()))
			.isEqualTo("COMPLETED");
		assertThat(this.fixtures.level(this.store, this.frame)).as("remise en stock annulée").isEqualTo(4);
		assertThat(count("select count(*) from stock_movement where source_id = ? and type = 'SALE_CANCELLATION'",
				sale.id())).isZero();
	}

	// --- Outils ------------------------------------------------------------------------------------

	private SaleResponse sell(PaymentMethod method) {
		return as(this.actor, () -> this.sales.create(new CreateSaleRequest(null, null, null, method, null, null,
				List.of(new CreateSaleRequest.Item(this.frame, 1, null))), this.store));
	}

	private long count(String sql, Object... args) {
		Long value = this.jdbc.queryForObject(sql, Long.class, args);
		return value != null ? value : 0;
	}

}
