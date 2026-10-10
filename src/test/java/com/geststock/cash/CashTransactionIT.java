package com.geststock.cash;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.cash.dto.CashOperationRequest;
import com.geststock.cash.dto.CloseCashSessionRequest;
import com.geststock.cash.dto.CustomerPaymentRequest;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.common.security.Permissions;
import com.geststock.customer.CustomerAccountService;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Caisse en transactions réelles (COMMIT) : concurrence sur une même caisse, et garde-fous de
 * la base (écriture hors caisse ouverte ou par un autre que le titulaire, caisse clôturée
 * figée, règlement client sans encaissement).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CashTransactionIT {

	@Autowired
	private CashService cash;

	@Autowired
	private CustomerPaymentService payments;

	@Autowired
	private CustomerAccountService accounts;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private UUID cashier;

	private UUID other;

	private UUID warehouse;

	private UUID customer;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.cashier = this.fixtures.user("caissier");
		this.other = this.fixtures.user("autre");
		this.warehouse = this.fixtures.warehouse("caisse");
		this.customer = this.jdbc.queryForObject(
				"insert into customer (code, name, credit_limit) values (?, 'Client caisse IT', 100000) returning id",
				UUID.class, "CLI-CS-" + this.warehouse.toString().substring(0, 8).toUpperCase());
	}

	@AfterEach
	void tearDown() {
		this.transactions.executeWithoutResult(status -> {
			for (String table : List.of("cash_entry", "cash_session", "customer_account_entry", "audit_log")) {
				this.jdbc.execute("alter table " + table + " disable trigger user");
			}
			this.jdbc.update("delete from cash_entry where session_id in (select id from cash_session where warehouse_id = ?)",
					this.warehouse);
			this.jdbc.update("delete from cash_session where warehouse_id = ?", this.warehouse);
			this.jdbc.update("delete from customer_account_entry where customer_id = ?", this.customer);
			this.jdbc.update("delete from audit_log where user_id in (?, ?)", this.cashier, this.other);
			for (String table : List.of("cash_entry", "cash_session", "customer_account_entry", "audit_log")) {
				this.jdbc.execute("alter table " + table + " enable trigger user");
			}
			this.jdbc.update("delete from customer where id = ?", this.customer);
		});
		this.fixtures.purge();
	}

	@Test
	void concurrentCashOutsNeverExceedTheDrawer() throws Exception {
		open(this.cashier, "10000");

		List<Callable<Boolean>> outs = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			outs.add(() -> {
				try {
					as(this.cashier, () -> this.cash.recordManual(
							new CashOperationRequest(CashEntryType.Direction.OUT, new BigDecimal("2000"), "Sortie")));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.CASH_INSUFFICIENT);
					return false;
				}
			});
		}

		assertThat(runConcurrently(outs)).filteredOn(Boolean::booleanValue).hasSize(5);
		assertThat(as(this.cashier, () -> this.cash.current().orElseThrow().expectedCash())).isEqualByComparingTo("0");
	}

	@Test
	void aUserCannotOpenTwoDrawersEvenConcurrently() throws Exception {
		List<Callable<Boolean>> opens = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			opens.add(() -> {
				try {
					open(this.cashier, "1000");
					return true;
				}
				catch (ApiException | DataIntegrityViolationException ex) {
					return false;
				}
			});
		}

		assertThat(runConcurrently(opens)).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(this.jdbc.queryForObject("select count(*) from cash_session where cashier_id = ? and status = 'OPEN'",
				Integer.class, this.cashier)).isEqualTo(1);
	}

	@Test
	void aCommittedCustomerPaymentCarriesBothItsCashEntryAndItsAccountEntry() {
		as(this.cashier, () -> this.accounts.recordCreditSale(this.customer, new BigDecimal("30000"), UUID.randomUUID(),
				"VTE-IT"));
		open(this.cashier, "0");

		as(this.cashier, () -> this.payments.pay(this.customer, new CustomerPaymentRequest(new BigDecimal("12000"),
				PaymentMethod.MOBILE_MONEY, "Règlement partiel")));

		assertThat(this.jdbc.queryForObject("""
				select count(*) from cash_entry c join customer_account_entry a
				  on a.source_id = c.source_id and a.entry_type = 'PAYMENT' and a.amount = c.amount
				where c.customer_id = ? and c.entry_type = 'CUSTOMER_PAYMENT' and c.payment_method = 'MOBILE_MONEY'""",
				Integer.class, this.customer)).isEqualTo(1);
		assertThat(this.accounts.creditStatus(this.customer).balance()).isEqualByComparingTo("18000");
	}

	@Test
	void theDatabaseRefusesAPaymentWithoutItsCashEntry() {
		as(this.cashier, () -> this.accounts.recordCreditSale(this.customer, new BigDecimal("30000"), UUID.randomUUID(),
				"VTE-IT"));

		assertThatThrownBy(() -> this.transactions.executeWithoutResult(status -> this.jdbc.update("""
				insert into customer_account_entry (customer_id, entry_type, direction, amount, balance_after, source_type,
				                                    source_id, created_by)
				values (?, 'PAYMENT', 'CREDIT', 30000, 0, 'CUSTOMER_PAYMENT', ?, ?)""", this.customer, UUID.randomUUID(),
				this.cashier)))
			.hasStackTraceContaining("règlement client sans encaissement en caisse");
	}

	@Test
	void theDatabaseRefusesEntriesOutsideTheHoldersOpenDrawer() {
		UUID session = open(this.cashier, "5000");

		// Un autre utilisateur ne peut pas écrire dans cette caisse.
		assertThatThrownBy(() -> insertEntry(session, "OPC-IT-1", this.other))
			.hasStackTraceContaining("seul le titulaire de la caisse");

		as(this.cashier, () -> this.cash.close(new CloseCashSessionRequest(new BigDecimal("5000"), null)));
		// Une caisse clôturée ne reçoit plus rien, et ne se modifie plus.
		assertThatThrownBy(() -> insertEntry(session, "OPC-IT-2", this.cashier))
			.hasStackTraceContaining("n'est pas ouverte");
		assertThatThrownBy(() -> this.jdbc.update("update cash_session set counted_cash = 999999, difference = 994999 "
				+ "where id = ?", session))
			.hasStackTraceContaining("une caisse clôturée ne se modifie plus");
		assertThatThrownBy(() -> this.jdbc.update("delete from cash_session where id = ?", session))
			.hasStackTraceContaining("La table cash_session est en ajout seul");
	}

	// --- Outils ------------------------------------------------------------------------------------

	private UUID open(UUID user, String openingBalance) {
		return as(user, () -> this.cash.open(new OpenCashSessionRequest(new BigDecimal(openingBalance), null, null),
				this.warehouse)).id();
	}

	private void insertEntry(UUID session, String number, UUID author) {
		this.jdbc.update("""
				insert into cash_entry (number, session_id, entry_type, direction, payment_method, amount, created_at, created_by)
				values (?, ?, 'CASH_IN', 'IN', 'CASH', 100, now(), ?)""", number, session, author);
	}

	private <T> T as(UUID user, Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		try {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(StockFixtures.authentication(user, Permissions.ALL.toArray(String[]::new)));
			SecurityContextHolder.setContext(context);
			return action.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

	/** Lance toutes les tâches au même instant et attend leurs résultats. */
	private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 8));
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
