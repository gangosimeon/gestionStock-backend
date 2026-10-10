package com.geststock.returns;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Retours clients et leurs lignes, en ajout seul : ni modification ni suppression proposées
 * (la base les refuse de toute façon). Écrits uniquement par {@link CustomerReturnService}.
 */
interface CustomerReturnRepository extends Repository<CustomerReturn, UUID>, JpaSpecificationExecutor<CustomerReturn> {

	<S extends CustomerReturn> S save(S customerReturn);

	Optional<CustomerReturn> findById(UUID id);

	@Query("select r from CustomerReturn r where r.saleId = :saleId order by r.createdAt")
	List<CustomerReturn> findBySale(@Param("saleId") UUID saleId);

	@Query("select count(r) > 0 from CustomerReturn r where r.saleId = :saleId")
	boolean existsBySale(@Param("saleId") UUID saleId);

	/** Déjà remboursé en caisse sur une vente (règlements REFUND de ses retours). */
	@Query("""
			select coalesce(sum(r.totalAmount), 0) from CustomerReturn r
			where r.saleId = :saleId and r.settlement = com.geststock.returns.ReturnSettlement.REFUND""")
	BigDecimal refundedOnSale(@Param("saleId") UUID saleId);

	/** Quantités et montants déjà retournés par ligne de vente : {@code [sale_line_id, quantité, montant]}. */
	@Query("""
			select l.saleLineId, sum(l.quantity), sum(l.amount) from CustomerReturnLine l
			where l.returnId in (select r.id from CustomerReturn r where r.saleId = :saleId)
			group by l.saleLineId""")
	List<Object[]> returnedOnSale(@Param("saleId") UUID saleId);

	@Query("select l from CustomerReturnLine l where l.returnId in :returnIds order by l.lineNumber")
	List<CustomerReturnLine> findLines(@Param("returnIds") Collection<UUID> returnIds);

	/** Prochaine valeur de la numérotation des retours (avoirs). */
	@Query(value = "select nextval('seq_customer_return_number')", nativeQuery = true)
	long nextNumber();

}
