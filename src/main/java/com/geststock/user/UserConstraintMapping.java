package com.geststock.user;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/**
 * Traduction des violations d'unicité en erreurs métier. Le service vérifie l'unicité avant
 * d'écrire ; ce mapping couvre la concurrence (deux créations simultanées du même identifiant).
 */
@Configuration(proxyBeanMethods = false)
class UserConstraintMapping {

	@Bean
	ConstraintErrorMapping userConstraints() {
		return () -> Map.of(
				"uk_app_user_username", ErrorCode.USERNAME_ALREADY_USED,
				"uk_app_user_email", ErrorCode.EMAIL_ALREADY_USED);
	}

}
