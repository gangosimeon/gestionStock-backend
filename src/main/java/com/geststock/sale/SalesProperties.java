package com.geststock.sale;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Règles de vente paramétrables ({@code geststock.sales.*}).
 *
 * @param maxDiscountPercent remise maximale accordée sur une ligne, en pourcentage
 */
@Validated
@ConfigurationProperties("geststock.sales")
public record SalesProperties(
		@NotNull @DecimalMin("0") @DecimalMax("100") @DefaultValue("20") BigDecimal maxDiscountPercent) {
}
