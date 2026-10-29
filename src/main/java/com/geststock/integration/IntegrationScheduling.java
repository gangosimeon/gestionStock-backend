package com.geststock.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Planification des jobs d'intégration (métriques, purge), activée seulement si l'un d'eux l'est : par défaut,
 * aucun planificateur n'est démarré.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression("${geststock.integration.metrics.enabled:false} or ${geststock.integration.purge.enabled:false}")
class IntegrationScheduling {
}
