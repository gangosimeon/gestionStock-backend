package com.geststock.appointment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.geststock.appointment.AppointmentStatus;

/**
 * Clôture d'un rendez-vous planifié.
 *
 * @param status COMPLETED (honoré) ou NO_SHOW (absent) à partir du jour du rendez-vous ;
 *               CANCELLED (annulé) à tout moment, avec {@code reason}
 * @param reason motif de l'annulation (obligatoire pour CANCELLED, ignoré sinon)
 */
@Schema(name = "AppointmentStatusRequest")
public record AppointmentStatusRequest(
		@NotNull @Schema(example = "COMPLETED") AppointmentStatus status,
		@Size(max = 500) @Schema(example = "Client indisponible") String reason) {
}
