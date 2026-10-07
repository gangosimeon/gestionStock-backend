package com.geststock.warehouse;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Doublons concurrents (code, nom, dépôt principal) traduits en erreurs métier. */
@Configuration(proxyBeanMethods = false)
class WarehouseConstraintMapping {

	@Bean
	ConstraintErrorMapping warehouseConstraints() {
		return () -> Map.of(
				"uk_warehouse_code", ErrorCode.WAREHOUSE_CODE_ALREADY_USED,
				"uk_warehouse_name", ErrorCode.WAREHOUSE_NAME_ALREADY_USED,
				"uk_warehouse_main", ErrorCode.CONFLICT);
	}

}
