package com.geststock.appointment;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.criteria.Expression;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.geststock.appointment.dto.AppointmentFilter;
import com.geststock.appointment.dto.AppointmentRequest;
import com.geststock.appointment.dto.AppointmentResponse;
import com.geststock.appointment.dto.AppointmentStatusRequest;
import com.geststock.audit.AuditEvent;
import com.geststock.audit.AuditService;
import com.geststock.common.api.PageQuery;
import com.geststock.common.api.SortWhitelist;
import com.geststock.common.security.ActorIdentity;
import com.geststock.customer.CustomerService;
import com.geststock.customer.CustomerSnapshot;
import com.geststock.exception.ConflictException;
import com.geststock.exception.ErrorCode;
import com.geststock.exception.InvalidRequestException;
import com.geststock.exception.NotFoundException;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Rendez-vous (V028, {@code docs/appointments.md}).
 *
 * <p>Un rendez-vous se prend dans le dépôt courant, dans le futur, sur un créneau libre (un seul
 * rendez-vous planifié par dépôt et par horaire, garanti par la base). Il vise un client du
 * fichier (nom repris de sa fiche) ou un prospect (nom et téléphone saisis). Planifié, il se
 * modifie ; il se clôt une fois : honoré ou absent à partir de son jour, annulé avec un motif.
 * Il ne se supprime pas.
 */
@Service
@Transactional(readOnly = true)
public class AppointmentService {

	static final SortWhitelist SORTS = new SortWhitelist(
			Map.of("dateTime", "scheduledAt", "scheduledAt", "scheduledAt", "createdAt", "createdAt", "customerName",
					"customerName", "status", "status"),
			Sort.by(Sort.Direction.ASC, "scheduledAt"));

	private final AppointmentRepository repository;

	private final CustomerService customers;

	private final WarehouseAccess warehouseAccess;

	private final AuditService audit;

	private final Clock clock;

	/** Fuseau de l'entreprise : heure locale des rendez-vous et découpage par jour. */
	private final ZoneId zone;

	AppointmentService(AppointmentRepository repository, CustomerService customers, WarehouseAccess warehouseAccess,
			AuditService audit, Clock clock, @Value("${geststock.dashboard.time-zone:Africa/Abidjan}") String zone) {
		this.repository = repository;
		this.customers = customers;
		this.warehouseAccess = warehouseAccess;
		this.audit = audit;
		this.clock = clock;
		this.zone = ZoneId.of(zone);
	}

	/** Rendez-vous du dépôt, les plus proches d'abord. */
	public Page<AppointmentResponse> list(AppointmentFilter filter, UUID warehouseId, PageQuery query) {
		return this.repository.findAll(matching(filter, warehouseId), query.toPageable(SORTS)).map(this::toResponse);
	}

	public AppointmentResponse get(UUID id) {
		return toResponse(loadVisible(id));
	}

	/** Prend un rendez-vous dans le dépôt donné. */
	@Transactional
	public AppointmentResponse create(AppointmentRequest request, UUID warehouseId) {
		Contact contact = contactOf(request);
		Instant at = futureInstant(request.dateTime());
		requireFreeSlot(warehouseId, at, null);
		Appointment appointment = new Appointment(warehouseId);
		appointment.plan(contact.customerId(), contact.name(), contact.phone(), at, trimToNull(request.note()));
		Appointment saved = this.repository.saveAndFlush(appointment);

		AppointmentResponse created = toResponse(saved);
		this.audit.record(AuditEvent.of("CREATE", "APPOINTMENT", saved.getId(), label(saved))
			.details("Rendez-vous de " + saved.getCustomerName() + " le " + created.dateTime())
			.warehouse(warehouseId)
			.after(created));
		return created;
	}

	/** Modifie un rendez-vous planifié (remplacement complet) ; un nouvel horaire doit être futur et libre. */
	@Transactional
	public AppointmentResponse update(UUID id, AppointmentRequest request) {
		loadVisible(id);
		Appointment appointment = this.repository.findForUpdate(id).orElseThrow(AppointmentService::notFound);
		if (appointment.getStatus().isFinal()) {
			throw new ConflictException(ErrorCode.APPOINTMENT_CLOSED, ErrorCode.APPOINTMENT_CLOSED.defaultMessage());
		}
		AppointmentResponse before = toResponse(appointment);
		Contact contact = contactOf(request);
		Instant at = request.dateTime().atZone(this.zone).toInstant();
		if (!at.equals(appointment.getScheduledAt())) {
			at = futureInstant(request.dateTime());
			requireFreeSlot(appointment.getWarehouseId(), at, id);
		}
		appointment.plan(contact.customerId(), contact.name(), contact.phone(), at, trimToNull(request.note()));
		Appointment saved = this.repository.saveAndFlush(appointment);

		AppointmentResponse updated = toResponse(saved);
		this.audit.record(AuditEvent.of("UPDATE", "APPOINTMENT", saved.getId(), label(saved))
			.details("Modification du rendez-vous de " + saved.getCustomerName() + " (" + updated.dateTime() + ")")
			.warehouse(saved.getWarehouseId())
			.before(before)
			.after(updated));
		return updated;
	}

	/** Clôt un rendez-vous planifié : honoré, absent ou annulé. */
	@Transactional
	public AppointmentResponse changeStatus(UUID id, AppointmentStatusRequest request) {
		loadVisible(id);
		Appointment appointment = this.repository.findForUpdate(id).orElseThrow(AppointmentService::notFound);
		if (appointment.getStatus().isFinal()) {
			throw new ConflictException(ErrorCode.APPOINTMENT_CLOSED, ErrorCode.APPOINTMENT_CLOSED.defaultMessage());
		}
		AppointmentStatus target = request.status();
		String reason = null;
		switch (target) {
			case SCHEDULED -> throw new InvalidRequestException(ErrorCode.VALIDATION_FAILED,
					"Statut attendu : COMPLETED, NO_SHOW ou CANCELLED.");
			case COMPLETED, NO_SHOW -> {
				LocalDate day = appointment.getScheduledAt().atZone(this.zone).toLocalDate();
				if (day.isAfter(LocalDate.now(this.clock.withZone(this.zone)))) {
					throw new ConflictException(ErrorCode.APPOINTMENT_NOT_YET_DUE,
							ErrorCode.APPOINTMENT_NOT_YET_DUE.defaultMessage());
				}
			}
			case CANCELLED -> {
				reason = trimToNull(request.reason());
				if (reason == null) {
					throw new InvalidRequestException(ErrorCode.APPOINTMENT_CANCELLATION_REASON_REQUIRED,
							ErrorCode.APPOINTMENT_CANCELLATION_REASON_REQUIRED.defaultMessage());
				}
			}
		}
		AppointmentResponse before = toResponse(appointment);
		appointment.close(target, Instant.now(this.clock), ActorIdentity.currentUserId().orElse(null), reason);
		Appointment saved = this.repository.saveAndFlush(appointment);

		AppointmentResponse closed = toResponse(saved);
		String action = switch (target) {
			case COMPLETED -> "COMPLETE";
			case NO_SHOW -> "NO_SHOW";
			default -> "CANCEL";
		};
		this.audit.record(AuditEvent.of(action, "APPOINTMENT", saved.getId(), label(saved))
			.details("Rendez-vous de " + saved.getCustomerName() + " (" + closed.dateTime() + ") : " + target
					+ (reason != null ? " — " + reason : ""))
			.warehouse(saved.getWarehouseId())
			.before(before)
			.after(closed));
		return closed;
	}

	// --- Règles -----------------------------------------------------------------------------

	/** Client du fichier (actif) ou prospect ; le téléphone est toujours connu. */
	private Contact contactOf(AppointmentRequest request) {
		String phone = trimToNull(request.phone());
		if (request.customerId() != null) {
			CustomerSnapshot customer = this.customers.getForAssignment(request.customerId(), true);
			String customerPhone = phone != null ? phone : trimToNull(customer.phone());
			if (customerPhone == null) {
				throw contactRequired();
			}
			return new Contact(customer.id(), customer.name(), customerPhone);
		}
		String name = trimToNull(request.customerName());
		if (name == null || phone == null) {
			throw contactRequired();
		}
		return new Contact(null, name, phone);
	}

	/** Heure locale du dépôt convertie en instant, refusée si elle n'est pas dans le futur. */
	private Instant futureInstant(LocalDateTime dateTime) {
		Instant at = dateTime.atZone(this.zone).toInstant();
		Instant now = Instant.now(this.clock);
		if (!at.isAfter(now)) {
			// Les deux heures, locales au dépôt : l'utilisateur voit pourquoi l'horaire est refusé.
			java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
			throw new InvalidRequestException(ErrorCode.APPOINTMENT_IN_PAST,
					"Un rendez-vous se planifie dans le futur : " + dateTime.format(format) + " est déjà passé (il est "
							+ LocalDateTime.ofInstant(now, this.zone).format(format) + ", heure du dépôt).");
		}
		return at;
	}

	private void requireFreeSlot(UUID warehouseId, Instant at, UUID exceptId) {
		if (this.repository.slotTaken(warehouseId, at, exceptId != null ? exceptId : new UUID(0, 0))) {
			throw new ConflictException(ErrorCode.APPOINTMENT_SLOT_TAKEN,
					ErrorCode.APPOINTMENT_SLOT_TAKEN.defaultMessage());
		}
	}

	// --- Outils -----------------------------------------------------------------------------

	/** Rendez-vous d'un dépôt visible ; sinon « introuvable ». */
	private Appointment loadVisible(UUID id) {
		Appointment appointment = this.repository.findById(id).orElseThrow(AppointmentService::notFound);
		if (!this.warehouseAccess.canSee(appointment.getWarehouseId())) {
			throw notFound();
		}
		return appointment;
	}

	private Specification<Appointment> matching(AppointmentFilter filter, UUID warehouseId) {
		Specification<Appointment> spec = (root, query, cb) -> cb.equal(root.get("warehouseId"), warehouseId);
		if (filter.status() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), filter.status()));
		}
		if (filter.customerId() != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), filter.customerId()));
		}
		if (filter.dateFrom() != null) {
			Instant from = filter.dateFrom().atStartOfDay(this.zone).toInstant();
			spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("scheduledAt"), from));
		}
		if (filter.dateTo() != null) {
			Instant to = filter.dateTo().plusDays(1).atStartOfDay(this.zone).toInstant();
			spec = spec.and((root, query, cb) -> cb.lessThan(root.get("scheduledAt"), to));
		}
		if (StringUtils.hasText(filter.search())) {
			String text = filter.search().trim().toLowerCase(Locale.ROOT);
			String pattern = "%" + escape(text) + "%";
			String digits = text.replaceAll("[^0-9]", "");
			spec = spec.and((root, query, cb) -> {
				Expression<String> phoneDigits = cb.function("regexp_replace", String.class, root.get("phone"),
						cb.literal("[^0-9]"), cb.literal(""), cb.literal("g"));
				return digits.isEmpty() ? cb.like(cb.lower(root.get("customerName")), pattern, '\\')
						: cb.or(cb.like(cb.lower(root.get("customerName")), pattern, '\\'),
								cb.like(phoneDigits, "%" + digits + "%"));
			});
		}
		return spec;
	}

	private AppointmentResponse toResponse(Appointment appointment) {
		return new AppointmentResponse(appointment.getId(), appointment.getWarehouseId(), appointment.getCustomerId(),
				appointment.getCustomerName(), appointment.getPhone(),
				LocalDateTime.ofInstant(appointment.getScheduledAt(), this.zone), appointment.getScheduledAt(),
				appointment.getStatus(), appointment.getNote(), appointment.getCancellationReason(),
				appointment.getClosedAt(), appointment.getClosedBy(), appointment.getCreatedAt(),
				appointment.getCreatedBy(), appointment.getUpdatedAt());
	}

	private String label(Appointment appointment) {
		return appointment.getCustomerName() + " · " + LocalDateTime.ofInstant(appointment.getScheduledAt(), this.zone);
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.APPOINTMENT_NOT_FOUND, ErrorCode.APPOINTMENT_NOT_FOUND.defaultMessage());
	}

	private static InvalidRequestException contactRequired() {
		return new InvalidRequestException(ErrorCode.APPOINTMENT_CONTACT_REQUIRED,
				ErrorCode.APPOINTMENT_CONTACT_REQUIRED.defaultMessage());
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	/** Personne attendue : client du fichier ({@code customerId}) ou prospect. */
	private record Contact(UUID customerId, String name, String phone) {
	}

}
