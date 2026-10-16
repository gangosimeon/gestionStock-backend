package com.geststock.quote;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux devis, réservé au module {@code quote}. */
interface QuoteRepository extends JpaRepository<Quote, UUID>, JpaSpecificationExecutor<Quote> {

	/** Devis verrouillé jusqu'à la fin de la transaction : deux actions concurrentes se sérialisent. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select q from Quote q where q.id = :id")
	Optional<Quote> findForUpdate(@Param("id") UUID id);

	Optional<Quote> findBySaleId(UUID saleId);

	/** Prochaine valeur de la numérotation des devis (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_quote_number')", nativeQuery = true)
	long nextNumber();

}
