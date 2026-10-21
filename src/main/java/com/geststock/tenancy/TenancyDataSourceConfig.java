package com.geststock.tenancy;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * {@code DataSource} unique de l'application métier : la source routée. Spring Boot n'en crée donc
 * aucune autre ; JPA, {@code JdbcTemplate} et Flyway métier l'utilisent.
 */
@Configuration(proxyBeanMethods = false)
class TenancyDataSourceConfig {

	@Bean
	@Primary
	DataSource dataSource(TenantDataSourceRegistry registry, DefaultTenant defaultTenant) {
		return new TenantRoutingDataSource(registry, defaultTenant);
	}

}
