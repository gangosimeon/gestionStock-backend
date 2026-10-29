package com.geststock.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Collecte planifiée des métriques du flux ({@link FeedMetrics}) : n'existe que si
 * {@code geststock.integration.metrics.enabled=true} (désactivée par défaut, aucune requête sinon).
 */
@Component
@ConditionalOnProperty(prefix = "geststock.integration.metrics", name = "enabled", havingValue = "true")
class FeedMetricsJob {

	private final FeedMetrics metrics;

	FeedMetricsJob(FeedMetrics metrics) {
		this.metrics = metrics;
	}

	@Scheduled(fixedDelayString = "${geststock.integration.metrics.interval:60s}",
			initialDelayString = "${geststock.integration.metrics.interval:60s}")
	void collect() {
		this.metrics.collect();
	}

}
