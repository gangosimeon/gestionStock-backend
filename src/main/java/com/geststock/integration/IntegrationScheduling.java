package com.geststock.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Planification du job des métriques d'intégration, activée seulement s'il l'est : par défaut, aucun planificateur
 * n'est démarré. La purge n'est plus planifiée : la maintenance de l'intégration la déclenche (G20-D10).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "geststock.integration.metrics", name = "enabled", havingValue = "true")
class IntegrationScheduling {
}
