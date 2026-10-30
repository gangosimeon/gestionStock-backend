package com.geststock.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.support.PostgresTestConfiguration;

import tools.jackson.databind.json.JsonMapper;

/**
 * Insertion groupée des événements minces {@code AVAILABILITY} : une seule instruction SQL par appel de
 * {@link IntegrationEvents#publishAvailability}, déduplication par transaction conservée, {@code write_id} dans
 * l'ordre des identifiants de produit (ordre déterministe).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class AvailabilityBatchInsertIT {

	/** Drapeau de publication (G13), dépendance d'{@code IntegrationEvents} construit par ce test. */
	@Autowired
	private PublicationFlag publicationFlag;

	@Autowired
	private IntegrationProperties properties;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		this.jdbc = new JdbcTemplate(this.dataSource);
		// G13 : la publication exige une liaison active (drapeau de l'entreprise) ; ce test publie sans liaison,
		// comme au lot 1 : le drapeau est levé pour la durée du test.
		this.jdbc.update("update integration_publication set enabled = true");
		this.jdbc.update("delete from integration_outbox");
	}

	@AfterEach
	void tearDown() {
		this.jdbc.update("update integration_publication set enabled = false");
		this.jdbc.update("delete from integration_outbox");
	}

	@Test
	void threeProductsAreWrittenWithASingleStatementInIdentifierOrder() {
		CountingDataSource counting = new CountingDataSource(this.dataSource);
		IntegrationEvents events = new IntegrationEvents(this.properties, new OutboxWriter(counting), this.jsonMapper,
				this.entityManager, this.publicationFlag);
		List<UUID> products = Stream.generate(UUID::randomUUID).limit(3).toList();
		UUID fourth = UUID.randomUUID();

		new TransactionTemplate(this.transactionManager).executeWithoutResult(status -> {
			events.publishAvailability(products);
			assertThat(counting.statements.get()).as("instructions SQL pour 3 produits").isEqualTo(1);

			// Même transaction : seuls les produits nouveaux sont écrits, toujours en une instruction.
			events.publishAvailability(List.of(products.get(1), fourth, products.get(0)));
			assertThat(counting.statements.get()).isEqualTo(2);
			events.publishAvailability(products);
			assertThat(counting.statements.get()).as("rien de nouveau : aucune instruction").isEqualTo(2);
		});

		List<UUID> written = this.jdbc.queryForList(
				"select object_id from integration_outbox where type = 'AVAILABILITY' order by write_id", UUID.class);
		List<UUID> firstCall = products.stream().sorted(Comparator.comparing(UUID::toString)).toList();
		assertThat(written).hasSize(4);
		assertThat(written.subList(0, 3)).as("write_id dans l'ordre des identifiants").containsExactlyElementsOf(firstCall);
		assertThat(written.get(3)).isEqualTo(fourth);
		assertThat(this.jdbc.queryForList("""
				select distinct operation || ':' || object_version || ':' || coalesce(payload::text, 'null')
				from integration_outbox""", String.class)).containsExactly("UPSERT:0:null");
	}

	/** Connexions de la transaction courante, avec le compte des instructions préparées. */
	static final class CountingDataSource extends TransactionAwareDataSourceProxy {

		final AtomicInteger statements = new AtomicInteger();

		CountingDataSource(DataSource target) {
			super(target);
		}

		@Override
		public Connection getConnection() throws SQLException {
			Connection connection = super.getConnection();
			return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						String name = method.getName();
						if (name.startsWith("prepareStatement") || name.equals("createStatement")
								|| name.startsWith("prepareCall")) {
							this.statements.incrementAndGet();
						}
						try {
							return method.invoke(connection, args);
						}
						catch (InvocationTargetException ex) {
							throw ex.getCause();
						}
					});
		}

	}

}
