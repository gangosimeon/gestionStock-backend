package com.geststock.expense;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux dépenses, réservé au module {@code expense}. */
interface ExpenseRepository extends JpaRepository<Expense, UUID>, JpaSpecificationExecutor<Expense> {

	/** Dépense relue sous verrou : deux annulations simultanées se sérialisent. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Expense e where e.id = :id")
	Optional<Expense> findForUpdate(@Param("id") UUID id);

	/** Prochaine valeur de la numérotation des dépenses (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_expense_number')", nativeQuery = true)
	long nextNumber();

}
