package com.geststock.stock;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/**
 * Filets de sécurité de la base traduits en erreurs métier (cas concurrents qui échappent
 * aux contrôles applicatifs).
 */
@Configuration(proxyBeanMethods = false)
class StockConstraintMapping {

	@Bean
	ConstraintErrorMapping stockConstraints() {
		return () -> Map.of(
				"uk_stock_movement_reversal_of_id", ErrorCode.MOVEMENT_ALREADY_REVERSED,
				"ck_stock_level_quantity", ErrorCode.STOCK_INSUFFICIENT,
				"ck_stock_movement_balance_after", ErrorCode.STOCK_INSUFFICIENT);
	}

}
