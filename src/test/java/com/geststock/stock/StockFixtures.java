package com.geststock.stock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Données de test du stock : dépôts, produits et utilisateurs créés en SQL, avec des codes
 * uniques par exécution, puis purgés. Les tables du stock étant protégées par trigger
 * (ajout seul, pas d'écriture directe), la purge désactive ces triggers le temps d'une
 * transaction : c'est réservé aux tests.
 */
public final class StockFixtures {

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	private final String run = Integer.toString(ThreadLocalRandom.current().nextInt(100_000, 999_999));

	private final List<UUID> warehouses = new ArrayList<>();

	private final List<UUID> products = new ArrayList<>();

	private final List<UUID> users = new ArrayList<>();

	private int sequence;

	public StockFixtures(JdbcTemplate jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	public UUID warehouse(String label) {
		UUID id = this.jdbc.queryForObject("insert into warehouse (code, name) values (?, ?) returning id", UUID.class,
				"ST-" + this.run + "-" + (++this.sequence), "Stock test " + label + " " + this.run + "-" + this.sequence);
		this.warehouses.add(id);
		return id;
	}

	public UUID product(String label, int purchasePrice) {
		UUID category = this.jdbc.queryForObject("select id from category where name = 'Montures'", UUID.class);
		UUID id = this.jdbc.queryForObject("""
				insert into product (sku, name, category_id, purchase_price, retail_price, wholesale_price)
				values (?, ?, ?, ?, ?, ?) returning id""", UUID.class, "STK-" + this.run + "-" + (++this.sequence),
				"Produit " + label, category, purchasePrice, purchasePrice * 2, purchasePrice * 2);
		this.products.add(id);
		return id;
	}

	public UUID user(String label) {
		UUID id = this.jdbc.queryForObject("""
				insert into app_user (username, last_name, must_change_password)
				values (?, ?, false) returning id""", UUID.class, "stk" + this.run + "-" + (++this.sequence),
				"Testeur " + label);
		this.users.add(id);
		return id;
	}

	public int level(UUID warehouseId, UUID productId) {
		List<Integer> quantities = this.jdbc.queryForList(
				"select quantity from stock_level where warehouse_id = ? and product_id = ?", Integer.class, warehouseId,
				productId);
		return quantities.isEmpty() ? 0 : quantities.get(0);
	}

	public int movementCount(UUID warehouseId, UUID productId) {
		return this.jdbc.queryForObject("select count(*) from stock_movement where warehouse_id = ? and product_id = ?",
				Integer.class, warehouseId, productId);
	}

	/**
	 * Invariant du stock sur les dépôts de test : pour chaque niveau, quantité = somme des
	 * mouvements = solde du dernier mouvement. Renvoie les couples en défaut.
	 */
	public List<String> invariantViolations() {
		if (this.warehouses.isEmpty()) {
			return List.of();
		}
		return this.jdbc.queryForList("""
				select l.warehouse_id || '/' || l.product_id || ' : niveau ' || l.quantity
				       || ', somme ' || coalesce(s.total, 0) || ', dernier solde ' || coalesce(m.balance_after, 0)
				from stock_level l
				left join (select warehouse_id, product_id, sum(quantity) as total from stock_movement
				           group by warehouse_id, product_id) s
				       on s.warehouse_id = l.warehouse_id and s.product_id = l.product_id
				left join stock_movement m on m.id = l.last_movement_id
				where l.warehouse_id = any(?)
				  and (l.quantity <> coalesce(s.total, 0) or l.quantity <> coalesce(m.balance_after, 0))""",
				String.class, (Object) this.warehouses.toArray(UUID[]::new));
	}

	/** Exécute l'action au nom d'un utilisateur (son UUID est le nom de l'authentification). */
	public static <T> T runAs(UUID userId, Supplier<T> action) {
		SecurityContext previous = SecurityContextHolder.getContext();
		try {
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication(userId, "stock:read", "stock:adjust", "stock:transfer"));
			SecurityContextHolder.setContext(context);
			return action.get();
		}
		finally {
			SecurityContextHolder.setContext(previous);
		}
	}

	public static Authentication authentication(UUID userId, String... permissions) {
		return UsernamePasswordAuthenticationToken.authenticated(userId.toString(), null,
				java.util.Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
	}

	public void purge() {
		if (this.warehouses.isEmpty() && this.products.isEmpty() && this.users.isEmpty()) {
			return;
		}
		UUID[] warehouseIds = this.warehouses.toArray(UUID[]::new);
		UUID[] productIds = this.products.toArray(UUID[]::new);
		UUID[] userIds = this.users.toArray(UUID[]::new);
		this.transactions.executeWithoutResult(status -> {
			this.jdbc.execute("alter table stock_level disable trigger user");
			this.jdbc.execute("alter table stock_movement disable trigger user");
			this.jdbc.update("delete from stock_level where warehouse_id = any(?) or product_id = any(?)", warehouseIds,
					productIds);
			String scope = "(warehouse_id = any(?) or product_id = any(?) or created_by = any(?))";
			this.jdbc.update("delete from stock_movement where reversal_of_id is not null and " + scope, warehouseIds,
					productIds, userIds);
			this.jdbc.update("delete from stock_movement where " + scope, warehouseIds, productIds, userIds);
			this.jdbc.execute("alter table stock_movement enable trigger user");
			this.jdbc.execute("alter table stock_level enable trigger user");
			this.jdbc.update("delete from product where id = any(?)", (Object) productIds);
			this.jdbc.update("delete from app_user where id = any(?)", (Object) userIds);
			this.jdbc.update("delete from warehouse where id = any(?)", (Object) warehouseIds);
		});
		this.warehouses.clear();
		this.products.clear();
		this.users.clear();
	}

}
