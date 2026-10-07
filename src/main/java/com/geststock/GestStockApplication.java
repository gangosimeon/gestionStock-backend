package com.geststock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Point d'entrée du backend GestStock.
 *
 * <p>Monolithe modulaire : un package par domaine fonctionnel (voir {@code package-info.java}
 * de chaque module) et un socle technique transverse ({@code config}, {@code security},
 * {@code common}, {@code exception}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GestStockApplication {

	public static void main(String[] args) {
		SpringApplication.run(GestStockApplication.class, args);
	}

}
