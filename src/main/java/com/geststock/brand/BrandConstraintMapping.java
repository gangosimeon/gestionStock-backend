package com.geststock.brand;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Doublon de nom concurrent traduit en erreur métier. */
@Configuration(proxyBeanMethods = false)
class BrandConstraintMapping {

	@Bean
	ConstraintErrorMapping brandConstraints() {
		return () -> Map.of("uk_brand_name", ErrorCode.BRAND_NAME_ALREADY_USED);
	}

}
