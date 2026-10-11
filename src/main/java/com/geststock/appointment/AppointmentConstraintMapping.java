package com.geststock.appointment;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Deux prises de rendez-vous simultanées sur le même créneau : la seconde est refusée proprement. */
@Configuration(proxyBeanMethods = false)
class AppointmentConstraintMapping {

	@Bean
	ConstraintErrorMapping appointmentConstraints() {
		return () -> Map.of("uk_appointment_slot", ErrorCode.APPOINTMENT_SLOT_TAKEN);
	}

}
