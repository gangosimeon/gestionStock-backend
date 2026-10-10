package com.geststock.sale;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.geststock.customer.PriceCategory;
import com.geststock.product.ProductSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

/** Calcul serveur des montants d'une ligne de vente (RG-SAL-5, RG-SAL-6). */
class SalePricingTest {

	private static final ProductSnapshot FRAME = new ProductSnapshot(UUID.randomUUID(), "MON-1", "Monture", true,
			new BigDecimal("15000.00"), new BigDecimal("35000.00"), new BigDecimal("28000.00"));

	@Test
	void theUnitPriceComesFromTheProductForTheAppliedTariff() {
		assertThat(SalePricing.unitPrice(FRAME, PriceCategory.RETAIL)).isEqualByComparingTo("35000");
		assertThat(SalePricing.unitPrice(FRAME, PriceCategory.WHOLESALE)).isEqualByComparingTo("28000");
	}

	@Test
	void aLineWithoutDiscount() {
		SalePricing.LineAmounts line = SalePricing.line(new BigDecimal("35000.00"), 2, BigDecimal.ZERO,
				new BigDecimal("15000.00"));

		assertThat(line.gross()).isEqualByComparingTo("70000");
		assertThat(line.discount()).isEqualByComparingTo("0");
		assertThat(line.net()).isEqualByComparingTo("70000");
		assertThat(line.costTotal()).isEqualByComparingTo("30000");
		assertThat(line.unitCost()).isEqualByComparingTo("15000");
	}

	@Test
	void theDiscountIsRoundedToTheNearestCent() {
		// 3 × 3 333,33 = 9 999,99 ; 12,5 % = 1 249,99875 → 1 250,00.
		SalePricing.LineAmounts line = SalePricing.line(new BigDecimal("3333.33"), 3, new BigDecimal("12.50"),
				new BigDecimal("1000"));

		assertThat(line.gross()).isEqualByComparingTo("9999.99");
		assertThat(line.discount()).isEqualByComparingTo("1250.00");
		assertThat(line.net()).isEqualByComparingTo("8749.99");
		assertThat(line.net().add(line.discount())).isEqualByComparingTo(line.gross());
	}

	@Test
	void aSaleBelowCostIsComputedNotHidden() {
		SalePricing.LineAmounts line = SalePricing.line(new BigDecimal("1000.00"), 1, new BigDecimal("20"),
				new BigDecimal("900.00"));

		assertThat(line.net()).isEqualByComparingTo("800");
		assertThat(line.net().subtract(line.costTotal())).as("marge négative visible").isEqualByComparingTo("-100");
	}

}
