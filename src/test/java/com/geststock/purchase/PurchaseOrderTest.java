package com.geststock.purchase;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.geststock.exception.ApiException;
import com.geststock.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Règles de la commande fournisseur, sans base ni Spring. */
class PurchaseOrderTest {

	private static final Instant T0 = Instant.parse("2026-10-09T08:00:00Z");

	@Test
	void totalsAreComputedFromQuantitiesAndUnitCosts() {
		PurchaseOrder order = order();
		PurchaseOrderLine frames = order.addLine(UUID.randomUUID(), "MON-1", "Monture", 3, new BigDecimal("15000"));
		PurchaseOrderLine lenses = order.addLine(UUID.randomUUID(), "LEN-1", "Lentilles", 4, new BigDecimal("4500.50"));

		assertThat(frames.getLineTotal()).isEqualByComparingTo("45000.00");
		assertThat(lenses.getLineTotal()).isEqualByComparingTo("18002.00");
		assertThat(order.getTotalAmount()).isEqualByComparingTo("63002.00");
		assertThat(frames.getLineNumber()).isEqualTo(1);
		assertThat(lenses.getLineNumber()).isEqualTo(2);
		assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.ORDERED);
		assertThat(order.getStatus().legacyStatus()).isEqualTo("PENDING");
	}

	@Test
	void aCostWithMoreThanTwoDecimalsIsRejected() {
		assertThatThrownBy(() -> order().addLine(UUID.randomUUID(), "MON-1", "Monture", 1, new BigDecimal("10.005")))
			.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> order().addLine(UUID.randomUUID(), "MON-1", "Monture", 0, BigDecimal.TEN))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> order().addLine(UUID.randomUUID(), "MON-1", "Monture", 1, new BigDecimal("-1")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void partialThenCompleteReceipt() {
		PurchaseOrder order = order();
		PurchaseOrderLine frames = order.addLine(UUID.randomUUID(), "MON-1", "Monture", 10, new BigDecimal("1000"));
		PurchaseOrderLine lenses = order.addLine(UUID.randomUUID(), "LEN-1", "Lentilles", 5, new BigDecimal("200"));

		frames.receive(4);
		order.recordReceipt(frames.amountOf(4), T0);

		assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
		assertThat(order.getStatus().legacyStatus()).as("le front peut encore réceptionner").isEqualTo("PENDING");
		assertThat(frames.remainingQuantity()).isEqualTo(6);
		assertThat(order.getReceivedAmount()).isEqualByComparingTo("4000");
		assertThat(order.getLastReceiptAt()).isEqualTo(T0);

		frames.receive(6);
		lenses.receive(5);
		order.recordReceipt(frames.amountOf(6).add(lenses.amountOf(5)), T0.plusSeconds(3600));

		assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
		assertThat(order.getStatus().legacyStatus()).isEqualTo("DELIVERED");
		assertThat(order.getReceivedAmount()).isEqualByComparingTo(order.getTotalAmount());
		assertThat(order.getLastReceiptAt()).isEqualTo(T0.plusSeconds(3600));
	}

	@Test
	void aLineCannotReceiveMoreThanItsRemainder() {
		PurchaseOrderLine line = order().addLine(UUID.randomUUID(), "MON-1", "Monture", 2, BigDecimal.ONE);
		line.receive(2);

		assertThatThrownBy(() -> line.receive(1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> line.receive(0)).isInstanceOf(IllegalArgumentException.class);
		assertThat(line.getReceivedQuantity()).isEqualTo(2);
	}

	@Test
	void aReceivedOrderIsNoLongerReceivable() {
		PurchaseOrder order = order();
		PurchaseOrderLine line = order.addLine(UUID.randomUUID(), "MON-1", "Monture", 1, BigDecimal.ONE);
		line.receive(1);
		order.recordReceipt(BigDecimal.ONE, T0);

		assertThatThrownBy(() -> order.recordReceipt(BigDecimal.ONE, T0))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_RECEIVABLE);
	}

	@Test
	void onlyAnOrderWithoutReceiptCanBeCancelled() {
		PurchaseOrder untouched = order();
		untouched.addLine(UUID.randomUUID(), "MON-1", "Monture", 2, BigDecimal.ONE);
		untouched.cancel(T0, UUID.randomUUID(), "Erreur de saisie");

		assertThat(untouched.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
		assertThat(untouched.getStatus().legacyStatus()).isEqualTo("CANCELLED");
		assertThat(untouched.getStatus().isReceivable()).isFalse();
		assertThat(untouched.getClosedAt()).isEqualTo(T0);
		assertThat(untouched.getCloseReason()).isEqualTo("Erreur de saisie");

		PurchaseOrder started = order();
		PurchaseOrderLine line = started.addLine(UUID.randomUUID(), "MON-1", "Monture", 2, BigDecimal.ONE);
		line.receive(1);
		started.recordReceipt(BigDecimal.ONE, T0);
		assertThatThrownBy(() -> started.cancel(T0, null, null))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_CANCELLABLE);
	}

	@Test
	void onlyAPartiallyReceivedOrderCanBeClosed() {
		PurchaseOrder order = order();
		PurchaseOrderLine line = order.addLine(UUID.randomUUID(), "MON-1", "Monture", 5, BigDecimal.ONE);

		assertThatThrownBy(() -> order.closeRemainder(T0, null, null))
			.extracting(ex -> ((ApiException) ex).getCode())
			.isEqualTo(ErrorCode.PURCHASE_ORDER_NOT_CLOSABLE);

		line.receive(3);
		order.recordReceipt(new BigDecimal("3"), T0);
		order.closeRemainder(T0.plusSeconds(60), null, "Reliquat abandonné");

		assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
		assertThat(order.getStatus().legacyStatus()).isEqualTo("DELIVERED");
		assertThat(order.getStatus().isReceivable()).isFalse();
	}

	@Test
	void linesAreFrozenOnceTheOrderHasMoved() {
		PurchaseOrder order = order();
		order.addLine(UUID.randomUUID(), "MON-1", "Monture", 1, BigDecimal.ONE);
		order.cancel(T0, null, null);

		assertThatThrownBy(() -> order.addLine(UUID.randomUUID(), "MON-2", "Autre", 1, BigDecimal.ONE))
			.isInstanceOf(IllegalStateException.class);
	}

	private static PurchaseOrder order() {
		return new PurchaseOrder("CMD-2026-000001", UUID.randomUUID(), "Essilor", UUID.randomUUID(), null, null, null);
	}

}
