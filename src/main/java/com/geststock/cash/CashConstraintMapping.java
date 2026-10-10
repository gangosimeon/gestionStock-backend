package com.geststock.cash;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Doublons concurrents (deuxième caisse ouverte, double encaissement) traduits en erreurs métier. */
@Configuration(proxyBeanMethods = false)
class CashConstraintMapping {

	@Bean
	ConstraintErrorMapping cashConstraints() {
		return () -> Map.of("uk_cash_session_open_cashier", ErrorCode.CASH_SESSION_ALREADY_OPEN, "uk_cash_entry_source",
				ErrorCode.CASH_ENTRY_ALREADY_RECORDED);
	}

}
