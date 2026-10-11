package com.geststock.appointment.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Prise ou modification d'un rendez-vous (remplacement complet). Contrat du front :
 * {@code {customerId?, customerName, phone, dateTime, note?}}.
 *
 * @param customerId   client du fichier : son nom est repris de sa fiche, son téléphone aussi si
 *                     {@code phone} est vide ; absent pour un prospect
 * @param customerName nom du prospect (ignoré avec un client du fichier)
 * @param phone        obligatoire pour un prospect ou un client sans téléphone
 * @param dateTime     date et heure locales du dépôt ({@code AAAA-MM-JJTHH:mm}), dans le futur
 */
@Schema(name = "AppointmentRequest")
public record AppointmentRequest(
		UUID customerId,
		@Size(max = 200) @Schema(example = "Mamadou Diallo") String customerName,
		@Size(max = 30) @Pattern(regexp = "^[0-9+() .-]*$", message = "numéro de téléphone invalide")
		@Schema(example = "77 123 45 67") String phone,
		@NotNull @Schema(example = "2026-10-14T10:30", type = "string", format = "date-time") LocalDateTime dateTime,
		@Size(max = 1000) @Schema(example = "Essayage des nouvelles montures") String note) {
}
