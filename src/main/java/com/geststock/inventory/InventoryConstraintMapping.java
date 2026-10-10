package com.geststock.inventory;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.geststock.exception.ConstraintErrorMapping;
import com.geststock.exception.ErrorCode;

/** Deux inventaires ouverts simultanément dans un dépôt : traduit en erreur métier. */
@Configuration(proxyBeanMethods = false)
class InventoryConstraintMapping {

	@Bean
	ConstraintErrorMapping inventoryConstraints() {
		return () -> Map.of("uk_inventory_session_open_warehouse", ErrorCode.INVENTORY_ALREADY_OPEN);
	}

}
