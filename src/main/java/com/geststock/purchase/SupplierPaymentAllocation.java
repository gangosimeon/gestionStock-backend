package com.geststock.purchase;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * Part d'un paiement fournisseur imputée sur une commande (table
 * {@code supplier_payment_allocation}, V025). La somme des imputations d'un paiement égale son
 * montant ; celle d'une commande égale son {@code paid_amount} (vérifié au COMMIT).
 */
@Entity
@Immutable
@Table(name = "supplier_payment_allocation")
public class SupplierPaymentAllocation {

	@EmbeddedId
	private Key id;

	@Column(name = "amount", nullable = false, precision = 14, scale = 2)
	private BigDecimal amount;

	protected SupplierPaymentAllocation() {
		// JPA
	}

	SupplierPaymentAllocation(UUID paymentId, UUID purchaseOrderId, BigDecimal amount) {
		this.id = new Key(paymentId, purchaseOrderId);
		this.amount = Objects.requireNonNull(amount, "amount");
	}

	public UUID getPaymentId() {
		return this.id.paymentId;
	}

	public UUID getPurchaseOrderId() {
		return this.id.purchaseOrderId;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	/** Clé composée (paiement, commande). */
	@Embeddable
	public static class Key implements Serializable {

		@Column(name = "payment_id", nullable = false)
		private UUID paymentId;

		@Column(name = "purchase_order_id", nullable = false)
		private UUID purchaseOrderId;

		protected Key() {
			// JPA
		}

		Key(UUID paymentId, UUID purchaseOrderId) {
			this.paymentId = Objects.requireNonNull(paymentId, "paymentId");
			this.purchaseOrderId = Objects.requireNonNull(purchaseOrderId, "purchaseOrderId");
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key key && this.paymentId.equals(key.paymentId)
					&& this.purchaseOrderId.equals(key.purchaseOrderId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.paymentId, this.purchaseOrderId);
		}

	}

}
