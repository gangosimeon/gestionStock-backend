package com.geststock.appointment.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.geststock.appointment.AppointmentStatus;

/**
 * Critères des rendez-vous du dépôt courant.
 *
 * @param search   texte cherché dans le nom et le téléphone (chiffres seuls comparés)
 * @param dateFrom rendez-vous de ce jour ou après (jour local du dépôt)
 * @param dateTo   rendez-vous de ce jour ou avant (jour local du dépôt)
 */
public record AppointmentFilter(String search, AppointmentStatus status, LocalDate dateFrom, LocalDate dateTo,
		UUID customerId,
		@io.swagger.v3.oas.annotations.media.Schema(description = "Agenda de tous les dépôts (permission warehouse:all)")
		Boolean allWarehouses) {
}
