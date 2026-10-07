package com.geststock.category;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Doublon de nom concurrent traduit en erreur métier. */
@Configuration(proxyBeanMethods = false)
class CategoryConstraintMapping {

	@Bean
	ConstraintErrorMapping categoryConstraints() {
		return () -> Map.of("uk_category_name", ErrorCode.CATEGORY_NAME_ALREADY_USED);
	}

}
