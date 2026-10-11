package com.geststock.appointment.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.geststock.appointment.AppointmentStatus;

/**
 * Rendez-vous (contrat du front {@code Appointment}).
 *
 * @param customerId         client du fichier, ou {@code null} pour un prospect
 * @param dateTime           date et heure locales du dépôt (fuseau de l'entreprise)
 * @param scheduledAt        même instant, en UTC
 * @param closedAt           passage à un statut final
 * @param cancellationReason motif, pour un rendez-vous annulé
 */
@Schema(name = "Appointment")
public record AppointmentResponse(
		UUID id,
		UUID warehouseId,
		UUID customerId,
		String customerName,
		String phone,
		@Schema(type = "string", example = "2026-10-14T10:30:00") LocalDateTime dateTime,
		Instant scheduledAt,
		AppointmentStatus status,
		String note,
		String cancellationReason,
		Instant closedAt,
		UUID closedByUserId,
		Instant createdAt,
		UUID createdByUserId,
		Instant updatedAt) {
}
