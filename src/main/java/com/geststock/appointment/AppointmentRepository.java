package com.geststock.appointment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux rendez-vous, réservé au module {@code appointment}. */
interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

	/** Rendez-vous relu sous verrou : modification et clôture simultanées se sérialisent. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Appointment a where a.id = :id")
	Optional<Appointment> findForUpdate(@Param("id") UUID id);

	/** Un autre rendez-vous planifié que {@code exceptId} occupe-t-il ce créneau dans ce dépôt ? */
	@Query("""
			select count(a) > 0 from Appointment a
			where a.warehouseId = :warehouseId and a.scheduledAt = :at
			  and a.status = com.geststock.appointment.AppointmentStatus.SCHEDULED
			  and a.id <> :exceptId""")
	boolean slotTaken(@Param("warehouseId") UUID warehouseId, @Param("at") Instant at,
			@Param("exceptId") UUID exceptId);

}
