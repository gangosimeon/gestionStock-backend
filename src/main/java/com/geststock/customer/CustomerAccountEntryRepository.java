package com.geststock.customer;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Compte client, en ajout seul : ni modification ni suppression proposées (la base les refuse
 * de toute façon). Écrit uniquement par {@link CustomerAccountService}.
 */
interface CustomerAccountEntryRepository extends Repository<CustomerAccountEntry, UUID> {

	<S extends CustomerAccountEntry> S save(S entry);

	/** Solde dû : débits − crédits (0 sans écriture). */
	@Query(value = """
			select coalesce(sum(case when direction = 'DEBIT' then amount else -amount end), 0)
			from customer_account_entry where customer_id = :customerId""", nativeQuery = true)
	BigDecimal balanceOf(@Param("customerId") UUID customerId);

	/** Soldes dus des clients indiqués ; un client sans écriture est absent du résultat. */
	@Query(value = """
			select customer_id, sum(case when direction = 'DEBIT' then amount else -amount end)
			from customer_account_entry where customer_id in (:customerIds) group by customer_id""", nativeQuery = true)
	List<Object[]> balancesOf(@Param("customerIds") Collection<UUID> customerIds);

	@Query("""
			select count(e) > 0 from CustomerAccountEntry e
			where e.sourceType = :sourceType and e.sourceId = :sourceId and e.type = :type""")
	boolean existsBySource(@Param("sourceType") String sourceType, @Param("sourceId") UUID sourceId,
			@Param("type") CustomerAccountEntryType type);

	@Query("select e from CustomerAccountEntry e where e.customerId = :customerId")
	Page<CustomerAccountEntry> findByCustomer(@Param("customerId") UUID customerId, Pageable pageable);

	/** Relevé limité aux dépôts indiqués (non vide) ; les écritures sans dépôt n'en font pas partie. */
	@Query("select e from CustomerAccountEntry e where e.customerId = :customerId and e.warehouseId in :warehouseIds")
	Page<CustomerAccountEntry> findByCustomerInWarehouses(@Param("customerId") UUID customerId,
			@Param("warehouseIds") Collection<UUID> warehouseIds, Pageable pageable);

	/** Solde net des écritures hors des dépôts indiqués (non vide), sans dépôt comprises. */
	@Query(value = """
			select coalesce(sum(case when direction = 'DEBIT' then amount else -amount end), 0)
			from customer_account_entry
			where customer_id = :customerId and (warehouse_id is null or warehouse_id not in (:warehouseIds))""",
			nativeQuery = true)
	BigDecimal balanceOutside(@Param("customerId") UUID customerId, @Param("warehouseIds") Collection<UUID> warehouseIds);

}
