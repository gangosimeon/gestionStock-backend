package com.geststock.settings;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** Logo des factures : zéro ou une ligne. */
interface InvoiceLogoRepository extends Repository<InvoiceLogo, UUID> {

	@Query("select l from InvoiceLogo l")
	Optional<InvoiceLogo> findCurrent();

	InvoiceLogo save(InvoiceLogo logo);

	void delete(InvoiceLogo logo);

	void flush();

}
