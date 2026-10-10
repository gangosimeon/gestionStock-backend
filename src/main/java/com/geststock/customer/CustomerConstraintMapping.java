package com.geststock.customer;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Doublons concurrents (code client, double imputation d'une pièce) traduits en erreurs métier. */
@Configuration(proxyBeanMethods = false)
class CustomerConstraintMapping {

	@Bean
	ConstraintErrorMapping customerConstraints() {
		return () -> Map.of("uk_customer_code", ErrorCode.CUSTOMER_CODE_ALREADY_USED, "uk_customer_account_entry_source",
				ErrorCode.CUSTOMER_ACCOUNT_ENTRY_ALREADY_RECORDED);
	}

}
