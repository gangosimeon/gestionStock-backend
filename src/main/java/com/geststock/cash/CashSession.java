package com.geststock.cash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;

/**
 * Session de caisse (table {@code cash_session}, V018) : la caisse d'un caissier dans un dépôt,
 * de l'ouverture à la clôture. Une fois clôturée, elle est figée (trigger
 * {@code fn_cash_session_guard}).
 */
@Entity
@Table(name = "cash_session")
public class CashSession extends AuditableEntity {

	@Column(name = "number", nullable = false, length = 30, updatable = false)
	private String number;

	@Column(name = "warehouse_id", nullable = false, updatable = false)
	private UUID warehouseId;

	@Column(name = "cashier_id", nullable = false, updatable = false)
	private UUID cashierId;

	@Column(name = "terminal", length = 50, updatable = false)
	private String terminal;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private CashSessionStatus status = CashSessionStatus.OPEN;

	@Column(name = "opening_balance", nullable = false, precision = 14, scale = 2, updatable = false)
	private BigDecimal openingBalance;

	@Column(name = "opened_at", nullable = false, updatable = false)
	private Instant openedAt;

	@Column(name = "opening_note", length = 500, updatable = false)
	private String openingNote;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "closed_by")
	private UUID closedBy;

	@Column(name = "counted_cash", precision = 14, scale = 2)
	private BigDecimal countedCash;

	@Column(name = "expected_cash", precision = 14, scale = 2)
	private BigDecimal expectedCash;

	@Column(name = "difference", precision = 14, scale = 2)
	private BigDecimal difference;

	@Column(name = "closing_note", length = 500)
	private String closingNote;

	protected CashSession() {
		// JPA
	}

	CashSession(String number, UUID warehouseId, UUID cashierId, String terminal, BigDecimal openingBalance,
			Instant openedAt, String openingNote) {
		this.number = Objects.requireNonNull(number, "number");
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
		this.cashierId = Objects.requireNonNull(cashierId, "cashierId");
		this.terminal = terminal;
		this.openingBalance = Objects.requireNonNull(openingBalance, "openingBalance");
		this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
		this.openingNote = openingNote;
	}

	/** Clôture : montants compté et attendu, et écart, figés. */
	void close(Instant at, UUID by, BigDecimal counted, BigDecimal expected, String note) {
		if (this.status != CashSessionStatus.OPEN) {
			throw new IllegalStateException("Caisse déjà clôturée : " + this.number);
		}
		this.status = CashSessionStatus.CLOSED;
		this.closedAt = Objects.requireNonNull(at, "at");
		this.closedBy = Objects.requireNonNull(by, "by");
		this.countedCash = Objects.requireNonNull(counted, "counted");
		this.expectedCash = Objects.requireNonNull(expected, "expected");
		this.difference = counted.subtract(expected);
		this.closingNote = note;
	}

	public boolean isOpen() {
		return this.status == CashSessionStatus.OPEN;
	}

	public String getNumber() {
		return this.number;
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public UUID getCashierId() {
		return this.cashierId;
	}

	public String getTerminal() {
		return this.terminal;
	}

	public CashSessionStatus getStatus() {
		return this.status;
	}

	public BigDecimal getOpeningBalance() {
		return this.openingBalance;
	}

	public Instant getOpenedAt() {
		return this.openedAt;
	}

	public String getOpeningNote() {
		return this.openingNote;
	}

	public Instant getClosedAt() {
		return this.closedAt;
	}

	public UUID getClosedBy() {
		return this.closedBy;
	}

	public BigDecimal getCountedCash() {
		return this.countedCash;
	}

	public BigDecimal getExpectedCash() {
		return this.expectedCash;
	}

	public BigDecimal getDifference() {
		return this.difference;
	}

	public String getClosingNote() {
		return this.closingNote;
	}

}
