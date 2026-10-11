package com.geststock.purchase;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Doublons concurrents traduits en erreurs métier. */
@Configuration(proxyBeanMethods = false)
class PurchaseConstraintMapping {

	@Bean
	ConstraintErrorMapping purchaseConstraints() {
		return () -> Map.of("uk_purchase_order_supplier_invoice", ErrorCode.SUPPLIER_INVOICE_NUMBER_ALREADY_USED);
	}

}
