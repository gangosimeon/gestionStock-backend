package com.geststock.appointment;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.geststock.appointment.dto.AppointmentFilter;
import com.geststock.appointment.dto.AppointmentRequest;
import com.geststock.appointment.dto.AppointmentResponse;
import com.geststock.appointment.dto.AppointmentStatusRequest;
import com.geststock.audit.Audited;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.PageResponse;
import com.geststock.common.security.Permissions;
import com.geststock.common.security.RequiresPermission;
import com.geststock.exception.ApiError;
import com.geststock.warehouse.CurrentWarehouse;
import com.geststock.warehouse.WarehouseContext;

/**
 * Rendez-vous du dépôt courant (contrat front {@code /api/appointments}). Pas de suppression :
 * un rendez-vous s'annule avec un motif ({@code PATCH /{id}/status}).
 */
@RestController
@RequestMapping("/api/appointments")
@Tag(name = "Rendez-vous")
class AppointmentController {

	private final AppointmentService appointments;

	AppointmentController(AppointmentService appointments) {
		this.appointments = appointments;
	}

	@GetMapping
	@RequiresPermission(Permissions.APPOINTMENT_READ)
	@Operation(summary = "Rechercher les rendez-vous",
			description = "Du dépôt courant, les plus proches d'abord. Filtres search (nom, téléphone), status, "
					+ "dateFrom, dateTo (AAAA-MM-JJ inclus, jour local), customerId.")
	ResponseEntity<PageResponse<AppointmentResponse>> list(@ParameterObject AppointmentFilter filter,
			@CurrentWarehouse WarehouseContext warehouse, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@Parameter(description = "dateTime | createdAt | customerName | status, asc | desc") @RequestParam(
					required = false) List<String> sort) {
		PageQuery query = new PageQuery(page, size, sort);
		return PageResponse.toResponse(this.appointments.list(filter, warehouse.id(), query), query);
	}

	@GetMapping("/{id}")
	@RequiresPermission(Permissions.APPOINTMENT_READ)
	@Operation(summary = "Consulter un rendez-vous")
	AppointmentResponse get(@PathVariable UUID id) {
		return this.appointments.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Audited(action = "CREATE", entityType = "APPOINTMENT", idParam = "")
	@RequiresPermission(Permissions.APPOINTMENT_MANAGE)
	@Operation(summary = "Prendre un rendez-vous",
			description = "Dans le dépôt courant, dans le futur, sur un créneau libre. Client du fichier (customerId) "
					+ "ou prospect (customerName + phone).")
	@ApiResponse(responseCode = "201", description = "Rendez-vous planifié")
	@ApiResponse(responseCode = "409", description = "APPOINTMENT_SLOT_TAKEN",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<AppointmentResponse> create(@Valid @RequestBody AppointmentRequest request,
			@CurrentWarehouse WarehouseContext warehouse) {
		AppointmentResponse created = this.appointments.create(request, warehouse.id());
		return ResponseEntity.created(URI.create("/api/appointments/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@Audited(action = "UPDATE", entityType = "APPOINTMENT", idParam = "id")
	@RequiresPermission(Permissions.APPOINTMENT_MANAGE)
	@Operation(summary = "Modifier un rendez-vous",
			description = "Remplacement complet, tant qu'il est planifié ; un nouvel horaire doit être futur et libre.")
	@ApiResponse(responseCode = "200", description = "Rendez-vous modifié")
	@ApiResponse(responseCode = "409", description = "APPOINTMENT_CLOSED ou APPOINTMENT_SLOT_TAKEN",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	AppointmentResponse update(@PathVariable UUID id, @Valid @RequestBody AppointmentRequest request) {
		return this.appointments.update(id, request);
	}

	@PatchMapping("/{id}/status")
	@Audited(action = "STATUS", entityType = "APPOINTMENT", idParam = "id")
	@RequiresPermission(Permissions.APPOINTMENT_MANAGE)
	@Operation(summary = "Clore un rendez-vous",
			description = "COMPLETED (honoré) ou NO_SHOW (absent) à partir du jour du rendez-vous ; CANCELLED avec un "
					+ "motif. Un rendez-vous clos ne change plus.")
	@ApiResponse(responseCode = "200", description = "Rendez-vous clos")
	@ApiResponse(responseCode = "409", description = "APPOINTMENT_CLOSED ou APPOINTMENT_NOT_YET_DUE",
			content = @Content(schema = @Schema(implementation = ApiError.class)))
	AppointmentResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody AppointmentStatusRequest request) {
		return this.appointments.changeStatus(id, request);
	}

}
