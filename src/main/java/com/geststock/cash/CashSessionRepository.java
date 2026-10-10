package com.geststock.cash;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux sessions de caisse, réservé au module {@code cash}. */
interface CashSessionRepository extends JpaRepository<CashSession, UUID>, JpaSpecificationExecutor<CashSession> {

	/** Caisse ouverte d'un utilisateur (au plus une : index {@code uk_cash_session_open_cashier}). */
	@Query("select s from CashSession s where s.cashierId = :cashierId and s.status = com.geststock.cash.CashSessionStatus.OPEN")
	Optional<CashSession> findOpenByCashier(@Param("cashierId") UUID cashierId);

	/** Prochaine valeur de la numérotation des sessions. */
	@Query(value = "select nextval('seq_cash_session_number')", nativeQuery = true)
	long nextNumber();

}
