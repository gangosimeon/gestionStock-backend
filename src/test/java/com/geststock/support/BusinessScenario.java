package com.geststock.support;

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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.common.security.Permissions;
import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;
import com.geststock.stock.StockFixtures;

/**
 * Outils des scénarios métier en transactions réelles (COMMIT / ROLLBACK, comme en production) :
 * exécution au nom d'un utilisateur, lancement simultané de tâches, et purge de toutes les pièces
 * des dépôts de test (tables en ajout seul : leurs triggers sont suspendus le temps de la purge).
 */
public final class BusinessScenario {

	/** Tables purgées, filles avant mères. */
	private static final List<String> TABLES = List.of("customer_return_line", "customer_return", "cash_entry",
			"cash_session", "sale_line", "sale", "goods_receipt_line", "goods_receipt", "purchase_order_line",
			"purchase_order", "inventory_line", "inventory_session", "customer_account_entry", "audit_log");

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	public BusinessScenario(JdbcTemplate jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	/** Exécute l'action au nom de {@code userId}, avec toutes les permissions. */
	public static <T> T as(UUID userId, Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		try {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(StockFixtures.authentication(userId, Permissions.ALL.toArray(String[]::new)));
			SecurityContextHolder.setContext(context);
			return action.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

	/** Code d'erreur métier d'une action refusée ({@code null} si elle a réussi). */
	public static ErrorCode refusal(Runnable action) {
		try {
			action.run();
			return null;
		}
		catch (ApiException ex) {
			return ex.getCode();
		}
	}

	/** Lance toutes les tâches au même instant et attend leurs résultats. */
	public static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 12));
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
				results.add(future.get(90, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	/**
	 * Supprime les pièces des dépôts, clients et fournisseurs de test, puis ces clients et
	 * fournisseurs. Les produits, dépôts, mouvements et utilisateurs relèvent de {@link StockFixtures}.
	 */
	public void purge(List<UUID> warehouses, List<UUID> customers, List<UUID> suppliers) {
		UUID[] w = warehouses.toArray(UUID[]::new);
		UUID[] c = customers.toArray(UUID[]::new);
		UUID[] s = suppliers.toArray(UUID[]::new);
		this.transactions.executeWithoutResult(status -> {
			TABLES.forEach(table -> this.jdbc.execute("alter table " + table + " disable trigger user"));
			this.jdbc.update("""
					delete from customer_return_line where return_id in
					    (select id from customer_return where warehouse_id = any(?))""", (Object) w);
			this.jdbc.update("delete from customer_return where warehouse_id = any(?)", (Object) w);
			this.jdbc.update("""
					delete from cash_entry where session_id in (select id from cash_session where warehouse_id = any(?))""",
					(Object) w);
			this.jdbc.update("delete from cash_session where warehouse_id = any(?)", (Object) w);
			this.jdbc.update("delete from sale_line where sale_id in (select id from sale where warehouse_id = any(?))",
					(Object) w);
			this.jdbc.update("delete from sale where warehouse_id = any(?)", (Object) w);
			this.jdbc.update("""
					delete from goods_receipt_line where goods_receipt_id in
					    (select id from goods_receipt where warehouse_id = any(?))""", (Object) w);
			this.jdbc.update("delete from goods_receipt where warehouse_id = any(?)", (Object) w);
			this.jdbc.update("""
					delete from purchase_order_line where purchase_order_id in
					    (select id from purchase_order where warehouse_id = any(?))""", (Object) w);
			this.jdbc.update("delete from purchase_order where warehouse_id = any(?)", (Object) w);
			this.jdbc.update("""
					delete from inventory_line where inventory_id in
					    (select id from inventory_session where warehouse_id = any(?))""", (Object) w);
			this.jdbc.update("delete from inventory_session where warehouse_id = any(?)", (Object) w);
			this.jdbc.update("delete from customer_account_entry where customer_id = any(?)", (Object) c);
			this.jdbc.update("delete from audit_log where warehouse_id = any(?)", (Object) w);
			TABLES.forEach(table -> this.jdbc.execute("alter table " + table + " enable trigger user"));
			this.jdbc.update("delete from customer where id = any(?)", (Object) c);
			this.jdbc.update("delete from supplier where id = any(?)", (Object) s);
		});
	}

}
