package com.geststock.supplier;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Doublons concurrents (nom, code) traduits en erreurs métier. */
@Configuration(proxyBeanMethods = false)
class SupplierConstraintMapping {

	@Bean
	ConstraintErrorMapping supplierConstraints() {
		return () -> Map.of("uk_supplier_name", ErrorCode.SUPPLIER_NAME_ALREADY_USED, "uk_supplier_code",
				ErrorCode.SUPPLIER_CODE_ALREADY_USED);
	}

}
