package com.geststock.appointment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.geststock.common.jpa.AuditableEntity;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;

/**
 * Rendez-vous d'un client ou d'un prospect dans un dépôt (table {@code appointment}, V028).
 * Modifiable tant qu'il est planifié ; clos (honoré, absent ou annulé), il est figé (trigger
 * {@code fn_appointment_guard}). Écrit uniquement par {@link AppointmentService}.
 */
@Entity
@Table(name = "appointment")
public class Appointment extends AuditableEntity {

	@Column(name = "warehouse_id", nullable = false, updatable = false)
	private UUID warehouseId;

	@Column(name = "customer_id")
	private UUID customerId;

	@Column(name = "customer_name", nullable = false, length = 200)
	private String customerName;

	@Column(name = "phone", nullable = false, length = 30)
	private String phone;

	@Column(name = "scheduled_at", nullable = false)
	private Instant scheduledAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private AppointmentStatus status = AppointmentStatus.SCHEDULED;

	@Column(name = "note", length = 1000)
	private String note;

	@Column(name = "closed_at")
	private Instant closedAt;

	@Column(name = "closed_by")
	private UUID closedBy;

	@Column(name = "cancellation_reason", length = 500)
	private String cancellationReason;

	protected Appointment() {
		// JPA
	}

	Appointment(UUID warehouseId) {
		this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
	}

	/** Qui, quand et pourquoi : refusé sur un rendez-vous clos. */
	void plan(UUID newCustomerId, String newCustomerName, String newPhone, Instant newScheduledAt, String newNote) {
		requireScheduled();
		this.customerId = newCustomerId;
		this.customerName = Objects.requireNonNull(newCustomerName, "customerName");
		this.phone = Objects.requireNonNull(newPhone, "phone");
		this.scheduledAt = Objects.requireNonNull(newScheduledAt, "scheduledAt");
		this.note = newNote;
	}

	/** Clôt le rendez-vous : honoré, absent, ou annulé (motif obligatoire). */
	void close(AppointmentStatus finalStatus, Instant at, UUID actor, String reason) {
		requireScheduled();
		if (!finalStatus.isFinal()) {
			throw new IllegalArgumentException("Statut final attendu : " + finalStatus);
		}
		if ((finalStatus == AppointmentStatus.CANCELLED) != (reason != null)) {
			throw new IllegalArgumentException("Motif exigé pour l'annulation, et pour elle seule.");
		}
		this.status = finalStatus;
		this.closedAt = Objects.requireNonNull(at, "at");
		this.closedBy = actor;
		this.cancellationReason = reason;
	}

	private void requireScheduled() {
		if (this.status.isFinal()) {
			throw new ConflictException(ErrorCode.APPOINTMENT_CLOSED, ErrorCode.APPOINTMENT_CLOSED.defaultMessage());
		}
	}

	public UUID getWarehouseId() {
		return this.warehouseId;
	}

	public UUID getCustomerId() {
		return this.customerId;
	}

	public String getCustomerName() {
		return this.customerName;
	}

	public String getPhone() {
		return this.phone;
	}

	public Instant getScheduledAt() {
		return this.scheduledAt;
	}

	public AppointmentStatus getStatus() {
		return this.status;
	}

	public String getNote() {
		return this.note;
	}

	public Instant getClosedAt() {
		return this.closedAt;
	}

	public UUID getClosedBy() {
		return this.closedBy;
	}

	public String getCancellationReason() {
		return this.cancellationReason;
	}

}
