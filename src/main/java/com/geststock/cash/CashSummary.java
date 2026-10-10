package com.geststock.cash;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Récapitulatif d'une session (RG-CSH-3), calculé à partir de ses seules écritures (plus de
 * rattachement des ventes par fenêtre horaire) :
 * <ul>
 * <li>{@code cashSalesTotal} : ventes encaissées en espèces − remboursements (annulations et
 * retours) en espèces ;</li>
 * <li>{@code totalIn} : apports et règlements clients en espèces, nets des apports corrigés ;</li>
 * <li>{@code totalOut} : sorties manuelles d'espèces, nettes des sorties corrigées ;</li>
 * <li>{@code expectedCash} = fond + ventes espèces + entrées − sorties ;</li>
 * <li>{@code totalsByMethod} : solde net par moyen de paiement (rapprochement Mobile Money,
 * virements).</li>
 * </ul>
 */
record CashSummary(BigDecimal cashSalesTotal, BigDecimal totalIn, BigDecimal totalOut, BigDecimal expectedCash,
		Map<PaymentMethod, BigDecimal> totalsByMethod) {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	/**
	 * @param openingBalance fond de caisse
	 * @param totals         lignes {@code [entry_type, payment_method, somme]} de la session
	 */
	static CashSummary of(BigDecimal openingBalance, Iterable<Object[]> totals) {
		BigDecimal sales = ZERO;
		BigDecimal in = ZERO;
		BigDecimal out = ZERO;
		Map<PaymentMethod, BigDecimal> byMethod = new EnumMap<>(PaymentMethod.class);
		for (PaymentMethod method : PaymentMethod.values()) {
			byMethod.put(method, ZERO);
		}
		for (Object[] row : totals) {
			CashEntryType type = CashEntryType.valueOf((String) row[0]);
			PaymentMethod method = PaymentMethod.valueOf((String) row[1]);
			BigDecimal amount = ((BigDecimal) row[2]).setScale(2);
			BigDecimal signed = type.direction() == CashEntryType.Direction.IN ? amount : amount.negate();
			byMethod.merge(method, signed, BigDecimal::add);
			if (method != PaymentMethod.CASH) {
				continue;
			}
			switch (type) {
				case SALE -> sales = sales.add(amount);
				case SALE_REFUND, RETURN_REFUND -> sales = sales.subtract(amount);
				case CUSTOMER_PAYMENT, CASH_IN -> in = in.add(amount);
				case CORRECTION_OUT -> in = in.subtract(amount);
				case CASH_OUT -> out = out.add(amount);
				case CORRECTION_IN -> out = out.subtract(amount);
			}
		}
		BigDecimal expected = openingBalance.add(sales).add(in).subtract(out);
		return new CashSummary(sales, in, out, expected, byMethod);
	}

}
