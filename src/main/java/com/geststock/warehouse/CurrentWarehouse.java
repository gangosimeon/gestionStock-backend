package com.geststock.warehouse;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injecte dans un paramètre de contrôleur le {@link WarehouseContext} de la requête : dépôt
 * demandé par l'en-tête {@code X-Warehouse-Id} si l'utilisateur y a accès, sinon son dépôt
 * par défaut (règles dans {@link WarehouseAccess}).
 *
 * <pre>{@code
 * @PostMapping("/api/stock/movements")
 * StockMovementResponse create(@CurrentWarehouse WarehouseContext warehouse, ...)
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentWarehouse {
}
