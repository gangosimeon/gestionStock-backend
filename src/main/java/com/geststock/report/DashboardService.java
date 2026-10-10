package com.geststock.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.alert.AlertCategory;
import com.geststock.alert.AlertService;
import com.geststock.alert.AlertType;
import com.geststock.alert.dto.AlertFilter;
import com.geststock.alert.dto.AlertResponse;
import com.geststock.common.security.Permissions;
import com.geststock.exception.InvalidRequestException;
import com.geststock.report.dto.DashboardResponse;
import com.geststock.report.dto.DashboardSummaryResponse;
import com.geststock.report.dto.PurchaseKpis;
import com.geststock.report.dto.SalesDayPoint;
import com.geststock.report.dto.SalesKpis;
import com.geststock.report.dto.StockDayPoint;
import com.geststock.report.dto.StockKpis;
import com.geststock.report.dto.TopProductPoint;

/**
 * Indicateurs du tableau de bord, calculés à chaque appel sur PostgreSQL (aucune donnée
 * simulée, aucun cache). Règles de calcul : {@code docs/dashboard.md}.
 *
 * <p>Toutes les lectures d'un appel se font dans une même transaction en lecture seule
 * (instantané cohérent entre ventes, retours et stock).
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

	/** Période maximale d'une requête (un an, année bissextile comprise). */
	static final int MAX_DAYS = 366;

	static final int MAX_TOP = 50;

	private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("dd/MM");

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final DashboardQueries queries;

	private final AlertService alerts;

	private final ZoneId zone;

	private final Clock clock;

	DashboardService(DashboardQueries queries, AlertService alerts, DashboardProperties properties, Clock clock) {
		this.queries = queries;
		this.alerts = alerts;
		this.zone = properties.timeZone();
		this.clock = clock;
	}

	/** Aujourd'hui dans le fuseau de l'entreprise. */
	public LocalDate today() {
		return LocalDate.now(this.clock.withZone(this.zone));
	}

	// --- Indicateurs ------------------------------------------------------------------------------------

	public DashboardSummaryResponse summary(Period period, UUID warehouseId) {
		List<SalesDayPoint> days = salesByDay(period, warehouseId);
		Object[] purchases = this.queries.purchases(period.from(), period.to(), this.zone, warehouseId);
		return new DashboardSummaryResponse(Instant.now(this.clock), warehouseId, period.from(), period.to(),
				salesKpis(days, this.queries.cancelledSales(period.from(), period.to(), this.zone, warehouseId)),
				new PurchaseKpis((Long) purchases[0], money((BigDecimal) purchases[1]), (Long) purchases[2],
						money((BigDecimal) purchases[3])),
				stockKpis(warehouseId));
	}

	/** Ventes jour par jour (chiffre d'affaires net et marge), jours sans vente compris. */
	public List<SalesDayPoint> salesByDay(Period period, UUID warehouseId) {
		return this.queries.salesByDay(period.from(), period.to(), this.zone, warehouseId).stream().map(row -> {
			BigDecimal net = row.grossSales().subtract(row.returns());
			BigDecimal cost = row.cost().subtract(row.restockedCost());
			return new SalesDayPoint(row.day(), row.count(), money(row.grossSales()), money(row.returns()), money(net),
					money(net.subtract(cost)));
		}).toList();
	}

	/** Produits les plus vendus de la période, par chiffre d'affaires net décroissant. */
	public List<TopProductPoint> topProducts(Period period, int limit, UUID warehouseId) {
		if (limit < 1 || limit > MAX_TOP) {
			throw new InvalidRequestException("Paramètre invalide : limit doit être compris entre 1 et " + MAX_TOP + ".");
		}
		return this.queries.topProducts(period.from(), period.to(), this.zone, warehouseId, limit).stream()
			.map(row -> new TopProductPoint(row.productId(), row.sku(), row.name(), row.quantity(), money(row.revenue()),
					money(row.profit())))
			.toList();
	}

	/**
	 * Stock en fin de chaque jour, reconstitué depuis le journal des mouvements : stock actuel
	 * moins les mouvements postérieurs à la fin du jour. Exact, puisque chaque niveau de stock
	 * est la somme de ses mouvements (invariant garanti par la base).
	 */
	public List<StockDayPoint> stockEvolution(Period period, UUID warehouseId) {
		LocalDate today = today();
		LocalDate to = period.to().isAfter(today) ? today : period.to();
		long current = (Long) this.queries.stockNow(warehouseId)[0];
		Map<LocalDate, Long> net = this.queries
			.netMovementsByDay(period.from().plusDays(1).atStartOfDay(this.zone).toInstant(), this.zone, warehouseId);
		// Stock en fin de « to » : stock actuel moins tout ce qui a bougé après.
		long stock = current;
		for (Map.Entry<LocalDate, Long> entry : net.entrySet()) {
			if (entry.getKey().isAfter(to)) {
				stock -= entry.getValue();
			}
		}
		List<StockDayPoint> points = new ArrayList<>();
		for (LocalDate day = to; !day.isBefore(period.from()); day = day.minusDays(1)) {
			points.add(0, new StockDayPoint(day, day.format(LABEL), stock));
			stock -= net.getOrDefault(day, 0L);
		}
		return points;
	}

	/**
	 * Tableau de bord du front en un appel : indicateurs du jour, ventes des 14 derniers jours,
	 * top 8 produits des 30 derniers jours, stock des 7 derniers jours.
	 */
	public DashboardResponse dashboard(UUID warehouseId) {
		LocalDate today = today();
		DashboardSummaryResponse summary = summary(new Period(today, today), warehouseId);
		return new DashboardResponse(summary.generatedAt(), warehouseId,
				new DashboardResponse.Kpis(summary.sales().netSales(), summary.sales().grossProfit(),
						summary.stock().units()),
				salesByDay(Period.lastDays(today, 14), warehouseId),
				topProducts(Period.lastDays(today, 30), 8, warehouseId),
				stockEvolution(Period.lastDays(today, 7), warehouseId), summary);
	}

	// --- Calculs --------------------------------------------------------------------------------------

	static SalesKpis salesKpis(List<SalesDayPoint> days, long cancelled) {
		long count = 0;
		BigDecimal gross = ZERO;
		BigDecimal returns = ZERO;
		BigDecimal profit = ZERO;
		for (SalesDayPoint day : days) {
			count += day.count();
			gross = gross.add(day.grossSales());
			returns = returns.add(day.returns());
			profit = profit.add(day.profit());
		}
		BigDecimal net = gross.subtract(returns);
		BigDecimal rate = net.signum() > 0
				? profit.multiply(BigDecimal.valueOf(100)).divide(net, 1, RoundingMode.HALF_UP) : null;
		BigDecimal basket = count > 0 ? gross.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP) : null;
		return new SalesKpis(count, gross, returns, net, net.subtract(profit), profit, rate, basket, cancelled);
	}

	private StockKpis stockKpis(UUID warehouseId) {
		Object[] stock = this.queries.stockNow(warehouseId);
		Integer out = null;
		Integer below = null;
		Integer low = null;
		if (hasAuthority(Permissions.STOCK_READ)) {
			// Mêmes règles que les alertes (seuil du produit, seuil par défaut, marge configurée).
			List<AlertResponse> stockAlerts = this.alerts.compute(new AlertFilter(List.of(AlertCategory.STOCK),
					List.of(AlertType.OUT_OF_STOCK, AlertType.BELOW_MINIMUM, AlertType.LOW_STOCK), null, null),
					warehouseId);
			out = count(stockAlerts, AlertType.OUT_OF_STOCK);
			below = count(stockAlerts, AlertType.BELOW_MINIMUM);
			low = count(stockAlerts, AlertType.LOW_STOCK);
		}
		return new StockKpis((Long) stock[0], money((BigDecimal) stock[1]), (Long) stock[2], out, below, low);
	}

	private static int count(List<AlertResponse> alerts, AlertType type) {
		return (int) alerts.stream().filter(a -> a.type() == type).count();
	}

	private static boolean hasAuthority(String permission) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null
				&& authentication.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
	}

	private static BigDecimal money(BigDecimal value) {
		return (value != null ? value : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
	}

	// --- Période -------------------------------------------------------------------------------------

	/** Période de dates incluses, dans le fuseau de l'entreprise. */
	public record Period(LocalDate from, LocalDate to) {

		public Period {
			if (from.isAfter(to)) {
				throw new InvalidRequestException("Paramètre invalide : from doit précéder to.");
			}
			if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
				throw new InvalidRequestException("Paramètre invalide : période limitée à " + MAX_DAYS + " jours.");
			}
		}

		/** Les {@code days} derniers jours, aujourd'hui compris. */
		public static Period lastDays(LocalDate today, int days) {
			if (days < 1 || days > MAX_DAYS) {
				throw new InvalidRequestException("Paramètre invalide : days doit être compris entre 1 et " + MAX_DAYS + ".");
			}
			return new Period(today.minusDays(days - 1L), today);
		}

		/** {@code from}/{@code to} explicites, sinon les {@code defaultDays} derniers jours. */
		public static Period resolve(LocalDate from, LocalDate to, Integer days, int defaultDays, LocalDate today) {
			if (from == null && to == null) {
				return lastDays(today, days != null ? days : defaultDays);
			}
			LocalDate end = to != null ? to : today;
			LocalDate start = from != null ? from : end.minusDays((days != null ? days : defaultDays) - 1L);
			return new Period(start, end);
		}

	}

}
