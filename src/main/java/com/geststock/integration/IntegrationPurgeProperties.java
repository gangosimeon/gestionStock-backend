package com.geststock.integration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Purge du flux d'intégration ({@code geststock.integration.purge.*}), livrée <b>désactivée</b> et en
 * <b>simulation</b> : la suppression réelle exige {@code enabled=true} et {@code mode=DELETE}, explicitement.
 *
 * @param enabled     job planifié actif
 * @param mode        {@code SIMULATION} (par défaut) ou {@code DELETE}
 * @param retention   âge minimal d'un événement purgeable (D5 : 90 jours au moins)
 * @param batchSize   lignes supprimées par lot (une transaction par lot, v17-D10)
 * @param lockTimeout attente maximale du verrou de la ligne d'état du flux, par lot
 * @param interval    intervalle entre deux passages du job
 * @param maxBatches  lots au plus par passage (v17-D16)
 * @param maxDuration durée au plus d'un passage, vérifiée entre deux lots : un lot commencé est mené à son terme, le
 *                    premier lot a toujours lieu (v17-D16)
 */
@ConfigurationProperties(prefix = "geststock.integration.purge")
public record IntegrationPurgeProperties(@DefaultValue("false") boolean enabled, @DefaultValue("SIMULATION") PurgeMode mode,
		@DefaultValue("90d") Duration retention, @DefaultValue("1000") int batchSize,
		@DefaultValue("2s") Duration lockTimeout, @DefaultValue("1h") Duration interval,
		@DefaultValue("100") int maxBatches, @DefaultValue("30s") Duration maxDuration) {
}
