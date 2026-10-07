package com.geststock.role;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Violations de contraintes concurrentes traduites en erreurs métier. */
@Configuration(proxyBeanMethods = false)
class RoleConstraintMapping {

	@Bean
	ConstraintErrorMapping roleConstraints() {
		return () -> Map.of(
				"uk_role_code", ErrorCode.ROLE_CODE_ALREADY_USED,
				"fk_user_role_role", ErrorCode.ROLE_IN_USE);
	}

}
