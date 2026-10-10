package com.geststock.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.geststock.alert.dto.AlertFilter;
import com.geststock.alert.dto.AlertResponse;
import com.geststock.alert.dto.AlertSummaryResponse;
import com.geststock.audit.AuditService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;

/**
 * Alertes calculées à la demande (API publique du module {@code alert}).
 *
 * <p>Rien n'est stocké ni envoyé : chaque appel relit l'état courant (stock, commandes,
 * comptes clients, caisses, inventaires) et applique les seuils de {@link AlertSettings}.
 * Une alerte disparaît donc d'elle-même quand sa cause est réglée. Chacun ne voit que les
 * domaines qu'il peut consulter ({@link AlertCategory#readPermission()}) et que les dépôts
 * auxquels il a accès.
 */
@Service
@Transactional(readOnly = true)
public class AlertService {

	/** Gravité, puis type, puis libellé : les plus urgentes d'abord, ordre stable. */
	static final Comparator<AlertResponse> ORDER = Comparator.comparing(AlertResponse::severity)
		.thenComparing(AlertResponse::type)
		.thenComparing(AlertResponse::entityLabel, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
		.thenComparing(AlertResponse::warehouseName, Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));

	private static final SortWhitelist NO_SORT = new SortWhitelist(Map.of(), Sort.unsorted());

	private static final int TOP_SIZE = 10;

	private final AlertQueries queries;

	private final AlertSettingRepository settings;

	private final AuditService audit;

	private final Clock clock;

	AlertService(AlertQueries queries, AlertSettingRepository settings, AuditService audit, Clock clock) {
		this.queries = queries;
		this.settings = settings;
		this.audit = audit;
		this.clock = clock;
	}

	// --- Alertes ------------------------------------------------------------------------------------

	/**
	 * Alertes visibles, triées par gravité.
	 *
	 * @param warehouseId dépôt couvert ({@code null} : tous les dépôts, droit vérifié par l'appelant)
	 */
	public List<AlertResponse> compute(AlertFilter filter, UUID warehouseId) {
		Set<AlertCategory> categories = visibleCategories();
		if (filter.category() != null && !filter.category().isEmpty()) {
			categories.retainAll(filter.category());
		}
		Set<AlertType> types = EnumSet.allOf(AlertType.class);
		if (filter.type() != null && !filter.type().isEmpty()) {
			types.retainAll(filter.type());
		}
		types.removeIf(type -> !categories.contains(type.category()));

		List<AlertResponse> alerts = evaluate(types, warehouseId);
		if (filter.severity() != null && !filter.severity().isEmpty()) {
			alerts.removeIf(alert -> !filter.severity().contains(alert.severity()));
		}
		alerts.sort(ORDER);
		return alerts;
	}

	public Page<AlertResponse> search(AlertFilter filter, UUID warehouseId, PageQuery query) {
		Pageable pageable = query.toPageable(NO_SORT);
		List<AlertResponse> alerts = compute(filter, warehouseId);
		int from = (int) Math.min(pageable.getOffset(), alerts.size());
		int to = Math.min(from + pageable.getPageSize(), alerts.size());
		return new PageImpl<>(alerts.subList(from, to), pageable, alerts.size());
	}

	public AlertSummaryResponse summary(UUID warehouseId) {
		List<AlertResponse> alerts = compute(new AlertFilter(null, null, null, null), warehouseId);
		Map<AlertSeverity, Integer> bySeverity = new EnumMap<>(AlertSeverity.class);
		for (AlertSeverity severity : AlertSeverity.values()) {
			bySeverity.put(severity, 0);
		}
		Map<AlertCategory, Integer> byCategory = new EnumMap<>(AlertCategory.class);
		Map<AlertType, Integer> byType = new EnumMap<>(AlertType.class);
		Set<AlertCategory> visible = visibleCategories();
		for (AlertType type : AlertType.values()) {
			if (visible.contains(type.category())) {
				byCategory.put(type.category(), 0);
				byType.put(type, 0);
			}
		}
		for (AlertResponse alert : alerts) {
			bySeverity.merge(alert.severity(), 1, Integer::sum);
			byCategory.merge(alert.category(), 1, Integer::sum);
			byType.merge(alert.type(), 1, Integer::sum);
		}
		int stockAlerts = byType.getOrDefault(AlertType.OUT_OF_STOCK, 0)
				+ byType.getOrDefault(AlertType.BELOW_MINIMUM, 0);
		return new AlertSummaryResponse(Instant.now(this.clock), warehouseId, alerts.size(), stockAlerts, bySeverity,
				byCategory, byType, alerts.subList(0, Math.min(TOP_SIZE, alerts.size())));
	}

	private List<AlertResponse> evaluate(Set<AlertType> types, UUID warehouseId) {
		AlertSettings s = currentValues();
		Instant now = Instant.now(this.clock);
		List<AlertResponse> alerts = new ArrayList<>();

		if (types.stream().anyMatch(type -> type.category() == AlertCategory.STOCK)) {
			boolean dormancy = types.contains(AlertType.DORMANT_STOCK) && s.dormantStockDays() > 0;
			for (AlertRules.StockPosition position : this.queries.stockPositions(warehouseId, dormancy)) {
				AlertRules.stock(position, s, now).stream().filter(a -> types.contains(a.type())).forEach(alerts::add);
			}
		}
		if (types.contains(AlertType.PURCHASE_ORDER_OVERDUE)) {
			LocalDate today = LocalDate.now(this.clock);
			for (AlertRules.OpenPurchaseOrder po : this.queries.overduePurchaseOrders(warehouseId,
					today.minusDays(s.purchaseOverdueGraceDays()))) {
				alerts.add(AlertRules.purchaseOverdue(po, today,
						po.expectedDeliveryDate().atStartOfDay(this.clock.getZone()).toInstant()));
			}
		}
		if (types.contains(AlertType.CUSTOMER_PAYMENT_OVERDUE) || types.contains(AlertType.CUSTOMER_CREDIT_LIMIT)) {
			for (AlertRules.CustomerDebt debt : this.queries.customerDebts(s.defaultPaymentTermsDays(), now)) {
				AlertRules.customer(debt, s, now).stream().filter(a -> types.contains(a.type())).forEach(alerts::add);
			}
		}
		if (types.contains(AlertType.CASH_SESSION_OPEN_TOO_LONG) && s.cashSessionMaxOpenHours() > 0) {
			this.queries.cashSessionsOpenSince(warehouseId, now.minus(Duration.ofHours(s.cashSessionMaxOpenHours())))
				.forEach(row -> alerts.add(AlertRules.cashOpenTooLong(row, s.cashSessionMaxOpenHours(), now)));
		}
		if (types.contains(AlertType.CASH_DIFFERENCE) && s.cashDifferenceLookbackDays() > 0) {
			this.queries
				.cashDifferences(warehouseId, now.minus(Duration.ofDays(s.cashDifferenceLookbackDays())),
						s.cashDifferenceTolerance())
				.forEach(row -> alerts.add(AlertRules.cashDifference(row, s.cashDifferenceTolerance())));
		}
		if (types.contains(AlertType.INVENTORY_AWAITING_VALIDATION)) {
			this.queries.inventoriesAwaitingValidation(warehouseId)
				.forEach(row -> alerts.add(AlertRules.inventoryAwaitingValidation(row)));
		}
		return alerts;
	}

	/** Domaines consultables par l'utilisateur courant. */
	private static Set<AlertCategory> visibleCategories() {
		Set<AlertCategory> categories = EnumSet.noneOf(AlertCategory.class);
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return categories;
		}
		Set<String> authorities = new HashSet<>();
		for (GrantedAuthority authority : authentication.getAuthorities()) {
			authorities.add(authority.getAuthority());
		}
		for (AlertCategory category : AlertCategory.values()) {
			if (authorities.contains(category.readPermission())) {
				categories.add(category);
			}
		}
		return categories;
	}

	// --- Paramètres ---------------------------------------------------------------------------------

	public AlertSettings currentValues() {
		return this.settings.findCurrent()
			.orElseThrow(() -> new IllegalStateException("Paramètres des alertes absents (migration V021)."))
			.values();
	}

	@Transactional
	public AlertSettings updateSettings(AlertSettings values) {
		AlertSetting setting = this.settings.findCurrentForUpdate()
			.orElseThrow(() -> new IllegalStateException("Paramètres des alertes absents (migration V021)."));
		AlertSettings before = setting.values();
		setting.apply(values);
		AlertSettings after = setting.values();
		this.audit.success("UPDATE", "ALERT_SETTING", setting.getId().toString(), "Paramètres des alertes",
				"Seuils des alertes modifiés. Avant : " + before + " ; après : " + after, after, null);
		return after;
	}

}
