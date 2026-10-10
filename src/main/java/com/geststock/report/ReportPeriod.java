package com.geststock.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.geststock.exception.InvalidRequestException;

/**
 * Période d'un rapport, dates incluses, dans le fuseau de l'entreprise. Bornée à dix ans ; les
 * séries journalières le sont à 366 jours.
 */
record ReportPeriod(LocalDate from, LocalDate to, ZoneId zone) {

	static final int MAX_DAYS = 3660;

	static final int DEFAULT_DAYS = 30;

	ReportPeriod {
		if (from.isAfter(to)) {
			throw new InvalidRequestException("Paramètre invalide : from doit précéder to.");
		}
		if (days(from, to) > MAX_DAYS) {
			throw new InvalidRequestException("Paramètre invalide : période limitée à " + MAX_DAYS + " jours.");
		}
	}

	/** Dates demandées ; à défaut, les 30 derniers jours (aujourd'hui compris). */
	static ReportPeriod resolve(LocalDate from, LocalDate to, LocalDate today, ZoneId zone) {
		LocalDate end = to != null ? to : today;
		return new ReportPeriod(from != null ? from : end.minusDays(DEFAULT_DAYS - 1L), end, zone);
	}

	long days() {
		return days(this.from, this.to);
	}

	/** Début du premier jour. */
	OffsetDateTime start() {
		return at(this.from.atStartOfDay(this.zone).toInstant());
	}

	/** Début du lendemain du dernier jour (borne exclue). */
	OffsetDateTime end() {
		return at(this.to.plusDays(1).atStartOfDay(this.zone).toInstant());
	}

	private static long days(LocalDate from, LocalDate to) {
		return ChronoUnit.DAYS.between(from, to) + 1;
	}

	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
