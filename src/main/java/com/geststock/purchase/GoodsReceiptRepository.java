package com.geststock.purchase;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Réceptions, en ajout seul : ni modification ni suppression proposées (la base les refuse
 * de toute façon). Les lignes sont écrites par {@link GoodsReceiptService}.
 */
interface GoodsReceiptRepository extends Repository<GoodsReceipt, UUID> {

	<S extends GoodsReceipt> S save(S receipt);

	@Query("select r from GoodsReceipt r where r.purchaseOrder.id = :orderId order by r.receivedAt, r.number")
	List<GoodsReceipt> findByOrder(@Param("orderId") UUID orderId);

	/** Lignes des réceptions indiquées, avec leur ligne de commande (référence et libellé). */
	@Query("""
			select l from GoodsReceiptLine l join fetch l.orderLine
			where l.receipt.id in :receiptIds order by l.lineNumber""")
	List<GoodsReceiptLine> findLines(@Param("receiptIds") Collection<UUID> receiptIds);

	/** Réceptions d'un fournisseur (historique d'achats), éventuellement limitées à un dépôt. */
	@Query(value = """
			select r from GoodsReceipt r join fetch r.purchaseOrder o
			where o.supplierId = :supplierId and (:warehouseId is null or r.warehouseId = :warehouseId)
			  and (lower(r.number) like :pattern escape '\\' or lower(o.number) like :pattern escape '\\')""",
			countQuery = """
					select count(r) from GoodsReceipt r
					where r.purchaseOrder.supplierId = :supplierId
					  and (:warehouseId is null or r.warehouseId = :warehouseId)
					  and (lower(r.number) like :pattern escape '\\'
					       or lower(r.purchaseOrder.number) like :pattern escape '\\')""")
	Page<GoodsReceipt> findSupplierHistory(@Param("supplierId") UUID supplierId,
			@Param("warehouseId") UUID warehouseId, @Param("pattern") String pattern, Pageable pageable);

	/** Prochaine valeur de la numérotation des réceptions. */
	@Query(value = "select nextval('seq_goods_receipt_number')", nativeQuery = true)
	long nextNumber();

}
