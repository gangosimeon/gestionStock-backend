package com.geststock.settings;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Ligne unique de la configuration des factures (créée par V035, jamais supprimée). */
interface InvoiceSettingRepository extends Repository<InvoiceSetting, UUID> {

	@Query("select s from InvoiceSetting s")
	Optional<InvoiceSetting> findCurrent();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from InvoiceSetting s")
	Optional<InvoiceSetting> findCurrentForUpdate();

	/** Écrit les modifications tout de suite, pour renvoyer la date de mise à jour réelle. */
	void flush();

}
