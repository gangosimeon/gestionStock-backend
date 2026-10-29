package com.geststock.integration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paramètres de la lecture du flux d'intégration ({@code geststock.integration.feed.*}).
 *
 * @param statementTimeout {@code statement_timeout} de la transaction de lecture
 */
@ConfigurationProperties(prefix = "geststock.integration.feed")
public record ChangeFeedProperties(@DefaultValue("5s") Duration statementTimeout) {
}
