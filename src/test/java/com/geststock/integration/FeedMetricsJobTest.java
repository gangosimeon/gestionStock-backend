package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Job des métriques : absent (aucune requête, aucun planificateur) tant que
 * {@code geststock.integration.metrics.enabled} n'est pas {@code true} ; actif, il collecte à l'intervalle configuré.
 */
class FeedMetricsJobTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(JobConfiguration.class);

	@Test
	void disabledByDefaultTheJobNeverQueries() {
		this.runner.run(context -> {
			assertThat(context).doesNotHaveBean(FeedMetricsJob.class).doesNotHaveBean(IntegrationScheduling.class);
			verify(context.getBean(FeedMetrics.class), after(500).never()).collect();
		});
		this.runner.withPropertyValues("geststock.integration.metrics.enabled=false").run(context -> {
			assertThat(context).doesNotHaveBean(FeedMetricsJob.class);
			verify(context.getBean(FeedMetrics.class), after(300).never()).collect();
		});
	}

	@Test
	void enabledTheJobCollectsAtTheConfiguredInterval() {
		this.runner.withPropertyValues("geststock.integration.metrics.enabled=true",
				"geststock.integration.metrics.interval=100ms").run(context -> {
					assertThat(context).hasSingleBean(FeedMetricsJob.class).hasSingleBean(IntegrationScheduling.class);
					verify(context.getBean(FeedMetrics.class), timeout(3000).atLeast(2)).collect();
				});
	}

	@Configuration(proxyBeanMethods = false)
	@Import({ FeedMetricsJob.class, IntegrationScheduling.class })
	static class JobConfiguration {

		@Bean
		FeedMetrics feedMetrics() {
			return mock(FeedMetrics.class);
		}

	}

}
