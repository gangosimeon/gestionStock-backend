package com.geststock.sale;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux ventes, réservé au module {@code sale}. */
interface SaleRepository extends JpaRepository<Sale, UUID>, JpaSpecificationExecutor<Sale> {

	/** Vente verrouillée jusqu'à la fin de la transaction : deux annulations se sérialisent. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Sale s where s.id = :id")
	Optional<Sale> findForUpdate(@Param("id") UUID id);

	/** Prochaine valeur de la numérotation des ventes (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_sale_number')", nativeQuery = true)
	long nextNumber();

}
