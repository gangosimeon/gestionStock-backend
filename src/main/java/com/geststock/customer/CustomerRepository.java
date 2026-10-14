package com.geststock.customer;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Accès aux clients, réservé au module {@code customer}. */
interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

	/** Code déjà pris (index {@code uk_customer_code}), hors l'élément indiqué. */
	@Query("""
			select count(c) > 0 from Customer c
			where c.code = :code and (:excludedId is null or c.id <> :excludedId)""")
	boolean existsByCode(@Param("code") String code, @Param("excludedId") UUID excludedId);

	/**
	 * Client actif de même nom (casse et espaces ignorés) et de même téléphone (chiffres seuls) :
	 * évite de recréer à la caisse un client déjà connu.
	 */
	@Query(value = """
			select id from customer
			where active and lower(btrim(name)) = lower(btrim(:name))
			  and regexp_replace(coalesce(phone, ''), '[^0-9]', '', 'g') = :phoneDigits
			order by created_at limit 1""", nativeQuery = true)
	java.util.Optional<UUID> findActiveByNameAndPhoneDigits(@Param("name") String name,
			@Param("phoneDigits") String phoneDigits);

	/** Prochaine valeur de la séquence des codes générés (jamais {@code max() + 1}). */
	@Query(value = "select nextval('seq_customer_code')", nativeQuery = true)
	long nextCodeNumber();

}
