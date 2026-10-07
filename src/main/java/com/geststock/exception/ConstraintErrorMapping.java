package com.geststock.exception;

import java.util.Map;

/**
 * Contribution d'un module à la traduction des violations de contraintes PostgreSQL en
 * erreurs métier lisibles.
 *
 * <p>Exemple, dans le module produit :
 * <pre>{@code
 * @Bean
 * ConstraintErrorMapping productConstraints() {
 *     return () -> Map.of("uk_product_sku", ErrorCode.SKU_ALREADY_EXISTS);
 * }
 * }</pre>
 * Les noms de contraintes suivent la convention Flyway {@code uk_/fk_/ck_<table>_...}.
 */
@FunctionalInterface
public interface ConstraintErrorMapping {

	/** Nom de contrainte (insensible à la casse) → code d'erreur à renvoyer. */
	Map<String, ErrorCode> mappings();

}
