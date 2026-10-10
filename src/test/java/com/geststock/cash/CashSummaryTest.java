package com.geststock.cash;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Solde de caisse calculé à partir des seules écritures (RG-CSH-3). */
class CashSummaryTest {

	@Test
	void expectedCashCountsOnlyCashMovements() {
		CashSummary summary = CashSummary.of(new BigDecimal("20000.00"), List.of(
				row("SALE", "CASH", "150000"),
				row("SALE", "MOBILE_MONEY", "80000"),
				row("SALE_REFUND", "CASH", "10000"),
				row("CUSTOMER_PAYMENT", "CASH", "25000"),
				row("CUSTOMER_PAYMENT", "BANK_TRANSFER", "40000"),
				row("CASH_IN", "CASH", "5000"),
				row("CASH_OUT", "CASH", "30000")));

		assertThat(summary.cashSalesTotal()).as("ventes espèces − remboursements espèces").isEqualByComparingTo("140000");
		assertThat(summary.totalIn()).as("règlements et apports en espèces").isEqualByComparingTo("30000");
		assertThat(summary.totalOut()).isEqualByComparingTo("30000");
		assertThat(summary.expectedCash()).as("20 000 + 140 000 + 30 000 − 30 000").isEqualByComparingTo("160000");
		assertThat(summary.totalsByMethod().get(PaymentMethod.CASH)).isEqualByComparingTo("140000");
		assertThat(summary.totalsByMethod().get(PaymentMethod.MOBILE_MONEY)).isEqualByComparingTo("80000");
		assertThat(summary.totalsByMethod().get(PaymentMethod.BANK_TRANSFER)).isEqualByComparingTo("40000");
	}

	@Test
	void returnRefundsAndCorrectionsAreNettedOut() {
		CashSummary summary = CashSummary.of(new BigDecimal("10000.00"), List.of(
				row("SALE", "CASH", "100000"),
				row("RETURN_REFUND", "CASH", "30000"),
				row("RETURN_REFUND", "MOBILE_MONEY", "5000"),
				row("CASH_IN", "CASH", "8000"),
				row("CORRECTION_OUT", "CASH", "8000"),
				row("CASH_OUT", "CASH", "4000"),
				row("CORRECTION_IN", "CASH", "4000")));

		assertThat(summary.cashSalesTotal()).as("100 000 − 30 000 remboursés").isEqualByComparingTo("70000");
		assertThat(summary.totalIn()).as("apport annulé par sa correction").isZero();
		assertThat(summary.totalOut()).as("sortie annulée par sa correction").isZero();
		assertThat(summary.expectedCash()).isEqualByComparingTo("80000");
		assertThat(summary.totalsByMethod().get(PaymentMethod.MOBILE_MONEY)).isEqualByComparingTo("-5000");
	}

	@Test
	void anEmptySessionHoldsItsOpeningBalance() {
		CashSummary summary = CashSummary.of(new BigDecimal("15000.00"), List.of());

		assertThat(summary.expectedCash()).isEqualByComparingTo("15000");
		assertThat(summary.cashSalesTotal()).isZero();
		assertThat(summary.totalsByMethod()).containsOnlyKeys(PaymentMethod.values());
	}

	private static Object[] row(String type, String method, String amount) {
		return new Object[] { type, method, new BigDecimal(amount) };
	}

}
