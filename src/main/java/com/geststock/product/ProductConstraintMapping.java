package com.geststock.product;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Doublons concurrents traduits en erreurs métier. */
@Configuration(proxyBeanMethods = false)
class ProductConstraintMapping {

	@Bean
	ConstraintErrorMapping productConstraints() {
		return () -> Map.of(
				"uk_product_sku", ErrorCode.SKU_ALREADY_USED,
				"uk_product_barcode", ErrorCode.BARCODE_ALREADY_USED);
	}

}
