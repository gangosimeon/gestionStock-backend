package com.geststock.alert;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Ligne unique des paramètres (créée par V021, jamais supprimée). */
interface AlertSettingRepository extends Repository<AlertSetting, UUID> {

	@Query("select s from AlertSetting s")
	Optional<AlertSetting> findCurrent();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from AlertSetting s")
	Optional<AlertSetting> findCurrentForUpdate();

}
