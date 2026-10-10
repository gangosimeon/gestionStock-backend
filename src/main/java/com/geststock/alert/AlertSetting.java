package com.geststock.alert;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/** Paramètres des alertes (ligne unique de {@code alert_setting}). 0 désactive l'alerte concernée. */
@Entity
@Table(name = "alert_setting")
class AlertSetting extends AuditableEntity {

	@Column(name = "singleton", nullable = false, updatable = false)
	private boolean singleton = true;

	@Column(name = "default_min_stock", nullable = false)
	private int defaultMinStock;

	@Column(name = "low_stock_margin_percent", nullable = false)
	private int lowStockMarginPercent;

	@Column(name = "dormant_stock_days", nullable = false)
	private int dormantStockDays;

	@Column(name = "purchase_overdue_grace_days", nullable = false)
	private int purchaseOverdueGraceDays;

	@Column(name = "default_payment_terms_days", nullable = false)
	private int defaultPaymentTermsDays;

	@Column(name = "credit_limit_warning_percent", nullable = false)
	private int creditLimitWarningPercent;

	@Column(name = "cash_session_max_open_hours", nullable = false)
	private int cashSessionMaxOpenHours;

	@Column(name = "cash_difference_tolerance", nullable = false, precision = 14, scale = 2)
	private BigDecimal cashDifferenceTolerance;

	@Column(name = "cash_difference_lookback_days", nullable = false)
	private int cashDifferenceLookbackDays;

	protected AlertSetting() {
	}

	AlertSettings values() {
		return new AlertSettings(this.defaultMinStock, this.lowStockMarginPercent, this.dormantStockDays,
				this.purchaseOverdueGraceDays, this.defaultPaymentTermsDays, this.creditLimitWarningPercent,
				this.cashSessionMaxOpenHours, this.cashDifferenceTolerance, this.cashDifferenceLookbackDays);
	}

	void apply(AlertSettings values) {
		this.defaultMinStock = values.defaultMinStock();
		this.lowStockMarginPercent = values.lowStockMarginPercent();
		this.dormantStockDays = values.dormantStockDays();
		this.purchaseOverdueGraceDays = values.purchaseOverdueGraceDays();
		this.defaultPaymentTermsDays = values.defaultPaymentTermsDays();
		this.creditLimitWarningPercent = values.creditLimitWarningPercent();
		this.cashSessionMaxOpenHours = values.cashSessionMaxOpenHours();
		this.cashDifferenceTolerance = values.cashDifferenceTolerance().setScale(2);
		this.cashDifferenceLookbackDays = values.cashDifferenceLookbackDays();
	}

}
