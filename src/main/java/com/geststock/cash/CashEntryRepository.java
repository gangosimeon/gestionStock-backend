package com.geststock.cash;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Écritures de caisse, en ajout seul : ni modification ni suppression proposées (la base les
 * refuse de toute façon). Écrites uniquement par {@link CashService}.
 */
interface CashEntryRepository extends Repository<CashEntry, UUID> {

	<S extends CashEntry> S save(S entry);

	/**
	 * Totaux par session, type et moyen de paiement : {@code [session_id, entry_type,
	 * payment_method, somme]}. Base du solde et des récapitulatifs de caisse.
	 */
	@Query(value = """
			select session_id, entry_type, payment_method, sum(amount)
			from cash_entry where session_id in (:sessionIds)
			group by session_id, entry_type, payment_method""", nativeQuery = true)
	List<Object[]> totalsOf(@Param("sessionIds") Collection<UUID> sessionIds);

	@Query("""
			select count(e) > 0 from CashEntry e
			where e.sourceType = :sourceType and e.sourceId = :sourceId and e.type = :type""")
	boolean existsBySource(@Param("sourceType") String sourceType, @Param("sourceId") UUID sourceId,
			@Param("type") CashEntryType type);

	Optional<CashEntry> findById(UUID id);

	@Query("select count(e) > 0 from CashEntry e where e.reversalOfId = :entryId")
	boolean existsByReversalOf(@Param("entryId") UUID entryId);

	@Query("select e from CashEntry e where e.sessionId = :sessionId")
	Page<CashEntry> findBySession(@Param("sessionId") UUID sessionId, Pageable pageable);

	/** Règlements d'un client ; {@code pattern} : motif LIKE (référence, note), « % » pour tous. */
	@Query("""
			select e from CashEntry e
			where e.customerId = :customerId and e.type = com.geststock.cash.CashEntryType.CUSTOMER_PAYMENT
			  and (lower(e.number) like :pattern escape '\\' or lower(coalesce(e.note, '')) like :pattern escape '\\')""")
	Page<CashEntry> findCustomerPayments(@Param("customerId") UUID customerId, @Param("pattern") String pattern,
			Pageable pageable);

	/** Règlements d'un client encaissés dans les dépôts indiqués (non vide). */
	@Query("""
			select e from CashEntry e
			where e.customerId = :customerId and e.type = com.geststock.cash.CashEntryType.CUSTOMER_PAYMENT
			  and e.sessionId in (select s.id from CashSession s where s.warehouseId in :warehouseIds)
			  and (lower(e.number) like :pattern escape '\\' or lower(coalesce(e.note, '')) like :pattern escape '\\')""")
	Page<CashEntry> findCustomerPaymentsInWarehouses(@Param("customerId") UUID customerId,
			@Param("pattern") String pattern, @Param("warehouseIds") Collection<UUID> warehouseIds, Pageable pageable);

	/** Prochaine valeur de la numérotation des écritures (référence OPC-…). */
	@Query(value = "select nextval('seq_cash_entry_number')", nativeQuery = true)
	long nextNumber();

}
