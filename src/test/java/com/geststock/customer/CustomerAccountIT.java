package com.geststock.customer;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.common.security.Permissions;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compte client en transactions réelles (COMMIT) : sous verrou du client, des ventes à crédit
 * simultanées ne dépassent jamais le plafond, des règlements simultanés jamais la dette, et
 * chaque solde après écriture enchaîne exactement les précédents.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class CustomerAccountIT {

	@Autowired
	private CustomerAccountService accounts;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private UUID actor;

	private UUID customer;

	@BeforeEach
	void setUp() {
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.actor = this.fixtures.user("vendeur");
		this.customer = this.jdbc.queryForObject("""
				insert into customer (code, name, credit_limit) values (?, 'Client concurrence', 100000) returning id""",
				UUID.class, "CLI-IT-" + this.actor.toString().substring(0, 8).toUpperCase());
	}

	@AfterEach
	void tearDown() {
		assertThat(chainViolations()).as("chaque solde = solde précédent ± montant").isEmpty();
		this.transactions.executeWithoutResult(status -> {
			this.jdbc.execute("alter table customer_account_entry disable trigger user");
			this.jdbc.update("delete from customer_account_entry where customer_id = ?", this.customer);
			this.jdbc.execute("alter table customer_account_entry enable trigger user");
			this.jdbc.update("delete from customer where id = ?", this.customer);
		});
		this.fixtures.purge();
	}

	@Test
	void concurrentCreditSalesNeverExceedTheCreditLimit() throws Exception {
		List<Callable<Boolean>> sales = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			sales.add(() -> {
				try {
					asSeller(() -> this.accounts.recordCreditSale(this.customer, new BigDecimal("15000"), UUID.randomUUID(),
							null));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.CREDIT_LIMIT_EXCEEDED);
					return false;
				}
			});
		}

		assertThat(runConcurrently(sales)).filteredOn(Boolean::booleanValue).hasSize(6);
		assertThat(balance()).isEqualByComparingTo("90000");
	}

	@Test
	void concurrentCreditsNeverExceedTheDebt() throws Exception {
		// Avoirs : un règlement (PAYMENT) exige en plus son encaissement en caisse (V018), voir CashTransactionIT.
		asSeller(() -> this.accounts.recordCreditSale(this.customer, new BigDecimal("50000"), UUID.randomUUID(), null));

		List<Callable<Boolean>> payments = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			payments.add(() -> {
				try {
					asSeller(() -> this.accounts.recordCredit(this.customer, CustomerAccountEntryType.CREDIT_NOTE,
							new BigDecimal("10000"), UUID.randomUUID(), null, null));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.AMOUNT_EXCEEDS_CUSTOMER_BALANCE);
					return false;
				}
			});
		}

		assertThat(runConcurrently(payments)).filteredOn(Boolean::booleanValue).hasSize(5);
		assertThat(balance()).isZero();
	}

	@Test
	void theSameSaleCannotBePostedTwiceEvenConcurrently() throws Exception {
		UUID sale = UUID.randomUUID();
		List<Callable<Boolean>> postings = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			postings.add(() -> {
				try {
					asSeller(() -> this.accounts.recordCreditSale(this.customer, new BigDecimal("1000"), sale, "VTE-1"));
					return true;
				}
				catch (ApiException ex) {
					assertThat(ex.getCode()).isEqualTo(ErrorCode.CUSTOMER_ACCOUNT_ENTRY_ALREADY_RECORDED);
					return false;
				}
			});
		}

		assertThat(runConcurrently(postings)).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(balance()).isEqualByComparingTo("1000");
	}

	@Test
	void creditNotesAndCancellationsReduceTheDebtButNeverBelowZero() {
		UUID sale = UUID.randomUUID();
		asSeller(() -> this.accounts.recordCreditSale(this.customer, new BigDecimal("30000"), sale, "VTE-9"));
		asSeller(() -> this.accounts.recordCredit(this.customer, CustomerAccountEntryType.CREDIT_NOTE,
				new BigDecimal("5000"), UUID.randomUUID(), "AV-1", "Retour d'une monture"));
		asSeller(() -> this.accounts.recordCredit(this.customer, CustomerAccountEntryType.SALE_CANCELLATION,
				new BigDecimal("25000"), sale, "VTE-9", null));

		assertThat(balance()).isZero();
		assertThat(this.accounts.creditStatus(this.customer).availableCredit()).isEqualByComparingTo("100000");
	}

	// --- Outils ----------------------------------------------------------------------------------

	private BigDecimal balance() {
		return this.jdbc.queryForObject("""
				select coalesce(sum(case when direction = 'DEBIT' then amount else -amount end), 0)
				from customer_account_entry where customer_id = ?""", BigDecimal.class, this.customer);
	}

	/** Écritures dont le solde n'est pas le solde cumulé (dans l'ordre d'écriture). */
	private List<String> chainViolations() {
		return this.jdbc.queryForList("""
				select id || ' : solde ' || balance_after || ', cumul ' || running
				from (select id, balance_after,
				             sum(case when direction = 'DEBIT' then amount else -amount end)
				                 over (order by created_at, id) as running
				      from customer_account_entry where customer_id = ?) e
				where balance_after <> running""", String.class, this.customer);
	}

	private <T> T asSeller(Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		try {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(StockFixtures.authentication(this.actor, Permissions.ALL.toArray(String[]::new)));
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
