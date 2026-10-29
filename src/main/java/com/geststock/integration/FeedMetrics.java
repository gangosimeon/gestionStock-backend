package com.geststock.integration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.geststock.tenancy.TenantDataSourceRegistry;
import com.geststock.tenancy.TenantDirectory;
import com.geststock.tenancy.TenantRef;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Métriques du flux d'intégration (lot 1, étape 8 ; décision Q5) : pour chaque entreprise active, une requête sur
 * sa base donne le nombre d'événements validés <b>sans {@code seq}</b> et l'âge du plus ancien (index partiel
 * {@code seq IS NULL}). Jauges Micrometer {@code integration.outbox.unsequenced} et
 * {@code integration.outbox.oldest.unsequenced.age} (secondes), étiquetées {@code company} = identifiant de
 * l'entreprise ; aucune exposition nouvelle (l'actuator n'expose que {@code health}).
 *
 * <p>Ce que mesure l'âge : le {@code seq} n'est attribué qu'à la lecture du flux (séquenceur appelé par la lecture
 * et par l'instantané). L'âge est donc le temps écoulé depuis l'écriture du plus ancien événement validé qu'<b>aucune
 * lecture n'a encore séquencé</b> : il monte si le séquenceur échoue, mais aussi, normalement, si aucun
 * consommateur ne lit le flux. Ce n'est un signal d'anomalie que pour une entreprise qui a un consommateur actif
 * (au lot 2 : une entreprise ne publie que si elle a une liaison active).
 *
 * <p>WARN au-delà du seuil ({@code warn-after}, 5 min par défaut) : identifiant de l'entreprise, nombre et âge,
 * jamais le contenu d'un événement. Une base indisponible est journalisée et n'empêche pas les autres ; ses jauges
 * gardent leur dernière valeur.
 */
@Component
class FeedMetrics {

	private static final Logger log = LoggerFactory.getLogger(FeedMetrics.class);

	static final String UNSEQUENCED = """
			select count(*) as unsequenced,
			       coalesce(floor(extract(epoch from now() - min(created_at))), 0)::bigint as oldest_age_seconds
			from integration_outbox
			where seq is null""";

	private final TenantDirectory directory;

	private final Function<TenantRef, DataSource> dataSources;

	private final MeterRegistry meters;

	private final IntegrationMetricsProperties properties;

	private final Map<UUID, CompanyGauges> gauges = new ConcurrentHashMap<>();

	@Autowired
	FeedMetrics(TenantDirectory directory, TenantDataSourceRegistry registry, MeterRegistry meters,
			IntegrationMetricsProperties properties) {
		this(directory, registry::dataSource, meters, properties);
	}

	FeedMetrics(TenantDirectory directory, Function<TenantRef, DataSource> dataSources, MeterRegistry meters,
			IntegrationMetricsProperties properties) {
		this.directory = directory;
		this.dataSources = dataSources;
		this.meters = meters;
		this.properties = properties;
	}

	/** Une collecte : chaque entreprise active, une requête chacune ; une erreur n'interrompt pas les suivantes. */
	void collect() {
		for (TenantRef tenant : this.directory.activeTenants()) {
			try {
				collect(tenant);
			}
			catch (RuntimeException ex) {
				log.warn("Métriques du flux d'intégration indisponibles pour l'entreprise {} : {}", tenant.companyId(),
						ex.getClass().getSimpleName());
			}
		}
	}

	private void collect(TenantRef tenant) {
		long[] values = JdbcClient.create(this.dataSources.apply(tenant)).sql(UNSEQUENCED)
			.query((rs, row) -> new long[] { rs.getLong("unsequenced"), rs.getLong("oldest_age_seconds") })
			.single();
		long count = values[0];
		long age = values[1];
		CompanyGauges company = this.gauges.computeIfAbsent(tenant.companyId(), this::register);
		company.unsequenced().set(count);
		company.oldestAge().set(age);
		if (count > 0 && age > this.properties.warnAfter().toSeconds()) {
			log.warn("Flux d'intégration de l'entreprise {} : {} événement(s) sans seq, le plus ancien depuis {} s "
					+ "(seuil {} s)", tenant.companyId(), count, age, this.properties.warnAfter().toSeconds());
		}
	}

	private CompanyGauges register(UUID companyId) {
		CompanyGauges company = new CompanyGauges(new AtomicLong(), new AtomicLong());
		Gauge.builder("integration.outbox.unsequenced", company.unsequenced(), AtomicLong::get)
			.description("Événements validés sans seq (non encore lus)")
			.tag("company", companyId.toString())
			.register(this.meters);
		Gauge.builder("integration.outbox.oldest.unsequenced.age", company.oldestAge(), AtomicLong::get)
			.description("Âge du plus ancien événement sans seq")
			.baseUnit("seconds")
			.tag("company", companyId.toString())
			.register(this.meters);
		return company;
	}

	private record CompanyGauges(AtomicLong unsequenced, AtomicLong oldestAge) {
	}

}
