package com.geststock.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.geststock.exception.InvalidRequestException;
import com.geststock.report.DashboardService.Period;
import com.geststock.report.dto.SalesDayPoint;
import com.geststock.report.dto.SalesKpis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Agrégation des indicateurs de vente et bornes des périodes. */
class DashboardServiceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

	@Test
	void salesKpisAddUpTheDaysAndDeriveMarginAndBasket() {
		SalesKpis kpis = DashboardService.salesKpis(List.of(
				day(1, 2, "73000", "31500", "41500", "22500"),
				day(2, 1, "10000", "0", "10000", "6000"),
				day(3, 0, "0", "0", "0", "0")), 1);

		assertThat(kpis.count()).isEqualTo(3);
		assertThat(kpis.grossSales()).isEqualByComparingTo("83000");
		assertThat(kpis.returns()).isEqualByComparingTo("31500");
		assertThat(kpis.netSales()).isEqualByComparingTo("51500");
		assertThat(kpis.grossProfit()).isEqualByComparingTo("28500");
		assertThat(kpis.costOfSales()).as("CA net − marge").isEqualByComparingTo("23000");
		assertThat(kpis.marginRate()).as("28 500 / 51 500").isEqualByComparingTo("55.3");
		assertThat(kpis.averageBasket()).as("83 000 / 3").isEqualByComparingTo("27666.67");
		assertThat(kpis.cancelledCount()).isEqualTo(1);
	}

	@Test
	void withoutSalesRatesAreAbsentRatherThanZero() {
		SalesKpis kpis = DashboardService.salesKpis(List.of(day(1, 0, "0", "0", "0", "0")), 0);

		assertThat(kpis.marginRate()).isNull();
		assertThat(kpis.averageBasket()).isNull();
		// Un jour de retours sans vente : CA net négatif, pas de taux de marge.
		assertThat(DashboardService.salesKpis(List.of(day(1, 0, "0", "5000", "-5000", "-1000")), 0).marginRate())
			.isNull();
	}

	@Test
	void periodsAreBoundedAndDefaulted() {
		assertThat(Period.resolve(null, null, null, 14, TODAY))
			.isEqualTo(new Period(LocalDate.of(2026, 9, 27), TODAY));
		assertThat(Period.resolve(null, null, 1, 14, TODAY)).isEqualTo(new Period(TODAY, TODAY));
		assertThat(Period.resolve(LocalDate.of(2026, 1, 1), null, null, 14, TODAY))
			.isEqualTo(new Period(LocalDate.of(2026, 1, 1), TODAY));
		assertThat(Period.resolve(null, LocalDate.of(2026, 3, 31), 31, 14, TODAY))
			.isEqualTo(new Period(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)));

		assertThatThrownBy(() -> new Period(TODAY, TODAY.minusDays(1))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> new Period(TODAY.minusDays(366), TODAY)).isInstanceOf(InvalidRequestException.class);
		assertThat(new Period(TODAY.minusDays(365), TODAY).to()).isEqualTo(TODAY);
		assertThatThrownBy(() -> Period.lastDays(TODAY, 0)).isInstanceOf(InvalidRequestException.class);
	}

	private static SalesDayPoint day(int dayOfMonth, long count, String gross, String returns, String net,
			String profit) {
		return new SalesDayPoint(LocalDate.of(2026, 10, dayOfMonth), count, new BigDecimal(gross).setScale(2),
				new BigDecimal(returns).setScale(2), new BigDecimal(net).setScale(2), new BigDecimal(profit).setScale(2));
	}

}
