package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

/**
 * Bail de la maintenance (G20-D4, G20-D12) dans la base plateforme : une seule ligne, génération croissante (jeton de
 * clôture), toutes les heures lues dans PostgreSQL. Un seul passage obtient le bail ; un bail expiré est repris avec la
 * génération suivante ; l'ancien propriétaire ne peut ni le vérifier, ni le prolonger, ni le libérer ; un bail expiré
 * n'est jamais prolongé. Chaque test laisse le bail libéré.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class MaintenanceLeaseIT {

	private static final Duration TTL = Duration.ofSeconds(50);

	@Autowired
	private MaintenanceLease lease;

	@Autowired
	private PlatformDb platform;

	private final List<MaintenanceLease.Lease> taken = new ArrayList<>();

	@AfterEach
	void releaseEverything() {
		this.taken.forEach(this.lease::release);
		this.taken.clear();
		// Bail laissé libre pour les autres classes de test (route de maintenance).
		this.platform.jdbc().sql("update platform.integration_maintenance_lease set released_at = clock_timestamp() "
				+ "where released_at is null and owner is not null").update();
	}

	@Test
	void concurrentPassesGetTheLeaseOnlyOnce() throws Exception {
		int contenders = 16;
		CyclicBarrier start = new CyclicBarrier(contenders);
		ExecutorService pool = Executors.newFixedThreadPool(contenders);
		try {
			List<Future<Optional<MaintenanceLease.Lease>>> results = new ArrayList<>();
			for (int i = 0; i < contenders; i++) {
				results.add(pool.submit(() -> {
					start.await(10, TimeUnit.SECONDS);
					return this.lease.acquire(TTL);
				}));
			}
			List<MaintenanceLease.Lease> winners = new ArrayList<>();
			for (Future<Optional<MaintenanceLease.Lease>> result : results) {
				result.get(30, TimeUnit.SECONDS).ifPresent(winners::add);
			}
			this.taken.addAll(winners);
			assertThat(winners).as("passages qui obtiennent le bail").hasSize(1);
			assertThat(this.lease.isHeld(winners.get(0))).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aReleasedLeaseIsTakenAgainWithTheNextGeneration() {
		MaintenanceLease.Lease first = take();
		assertThat(this.lease.acquire(TTL)).as("bail déjà pris").isEmpty();
		assertThat(this.lease.release(first)).isTrue();
		assertThat(this.lease.isHeld(first)).as("bail libéré").isFalse();
		assertThat(this.lease.release(first)).as("seconde libération").isFalse();

		MaintenanceLease.Lease second = take();
		assertThat(second.generation()).isEqualTo(first.generation() + 1);
		assertThat(second.owner()).isNotEqualTo(first.owner());
	}

	@Test
	void anExpiredLeaseIsTakenOverAndTheOldOwnerLosesEveryRight() {
		MaintenanceLease.Lease old = take();
		expire();
		assertThat(this.lease.isHeld(old)).as("bail expiré").isFalse();
		assertThat(this.lease.extend(old, TTL)).as("prolongation d'un bail expiré").isFalse();

		MaintenanceLease.Lease current = take();
		assertThat(current.generation()).isEqualTo(old.generation() + 1);
		// L'ancien passage, arrêté puis réveillé, vérifie son bail avant son lot suivant : il s'arrête.
		assertThat(this.lease.isHeld(old)).isFalse();
		assertThat(this.lease.extend(old, TTL)).isFalse();
		assertThat(this.lease.release(old)).as("libération par l'ancien propriétaire").isFalse();
		assertThat(this.lease.isHeld(current)).as("bail du nouveau passage intact").isTrue();
		assertThat(this.lease.acquire(TTL)).isEmpty();
	}

	@Test
	void aHeldLeaseIsExtendedFromTheDatabaseClock() {
		MaintenanceLease.Lease held = take();
		this.platform.jdbc().sql("update platform.integration_maintenance_lease set expires_at = clock_timestamp() + interval '2 seconds'")
			.update();
		assertThat(this.lease.extend(held, TTL)).isTrue();
		Double remaining = this.platform.jdbc()
			.sql("select extract(epoch from expires_at - clock_timestamp())::float8 from platform.integration_maintenance_lease")
			.query(Double.class).single();
		assertThat(remaining).as("secondes restantes après prolongation").isGreaterThan(40);
		assertThat(this.lease.isHeld(held)).isTrue();
	}

	@Test
	void theLeaseIsASingleRowWithoutSecret() {
		assertThat(this.platform.jdbc().sql("select count(*) from platform.integration_maintenance_lease").query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(this.platform.jdbc().sql("""
				select column_name from information_schema.columns
				where table_schema = 'platform' and table_name = 'integration_maintenance_lease' order by ordinal_position""")
			.query(String.class).list())
			.containsExactly("id", "generation", "owner", "acquired_at", "expires_at", "released_at");
	}

	private MaintenanceLease.Lease take() {
		MaintenanceLease.Lease lease = this.lease.acquire(TTL).orElseThrow(() -> new AssertionError("bail attendu"));
		this.taken.add(lease);
		return lease;
	}

	/** Échéance forcée dans le passé (processus arrêté, instance endormie). */
	private void expire() {
		this.platform.jdbc().sql("""
				update platform.integration_maintenance_lease
				set acquired_at = clock_timestamp() - interval '2 minutes', expires_at = clock_timestamp() - interval '1 second'
				where released_at is null""").update();
	}

}
