package com.geststock.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.config.ClockConfig;
import com.geststock.config.JpaConfig;

/**
 * Test de la couche JPA sur un vrai PostgreSQL (jamais de base embarquée) : migrations
 * Flyway jouées, schéma validé par Hibernate, audit technique JPA actif. Chaque test
 * s'exécute dans une transaction annulée à la fin.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ PostgresTestConfiguration.class, JpaConfig.class, ClockConfig.class })
public @interface PostgresDataJpaTest {

}
