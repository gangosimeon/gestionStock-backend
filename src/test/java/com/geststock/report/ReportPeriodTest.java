package com.geststock.report;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.geststock.exception.InvalidRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Période et filtres de statut des rapports. */
class ReportPeriodTest {

	private static final ZoneId ABIDJAN = ZoneId.of("Africa/Abidjan");

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

	@Test
	void theDefaultPeriodIsTheLastThirtyDays() {
		ReportPeriod period = ReportPeriod.resolve(null, null, TODAY, ABIDJAN);

		assertThat(period.from()).isEqualTo(LocalDate.of(2026, 9, 11));
		assertThat(period.to()).isEqualTo(TODAY);
		assertThat(period.days()).isEqualTo(30);
		assertThat(period.start().toString()).isEqualTo("2026-09-11T00:00Z");
		assertThat(period.end().toString()).as("borne exclue : lendemain du dernier jour").isEqualTo("2026-10-11T00:00Z");
		assertThat(ReportPeriod.resolve(null, LocalDate.of(2026, 3, 31), TODAY, ABIDJAN).from())
			.isEqualTo(LocalDate.of(2026, 3, 2));
	}

	@Test
	void periodsAreValidated() {
		assertThatThrownBy(() -> ReportPeriod.resolve(TODAY, TODAY.minusDays(1), TODAY, ABIDJAN))
			.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> ReportPeriod.resolve(TODAY.minusYears(11), TODAY, TODAY, ABIDJAN))
			.isInstanceOf(InvalidRequestException.class);
		assertThat(ReportPeriod.resolve(TODAY.minusYears(5), TODAY, TODAY, ABIDJAN).days()).isGreaterThan(1800);
	}

	@Test
	void statusesAreNormalisedAndWhitelisted() {
		assertThat(ReportSql.status(" completed ", Set.of("COMPLETED", "CANCELLED"))).isEqualTo("COMPLETED");
		assertThat(ReportSql.status(null, Set.of("COMPLETED"))).isNull();
		assertThat(ReportSql.status("  ", Set.of("COMPLETED"))).isNull();
		assertThatThrownBy(() -> ReportSql.status("PAID'; drop table sale; --", Set.of("COMPLETED")))
			.isInstanceOf(InvalidRequestException.class);
	}

}
