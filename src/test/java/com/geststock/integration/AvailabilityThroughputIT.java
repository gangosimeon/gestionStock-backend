package com.geststock.integration;

import static com.geststock.support.BusinessScenario.as;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import com.geststock.cash.CashService;
import com.geststock.cash.PaymentMethod;
import com.geststock.cash.dto.OpenCashSessionRequest;
import com.geststock.sale.SaleService;
import com.geststock.sale.dto.CreateSaleRequest;
import com.geststock.stock.StockFixtures;
import com.geststock.stock.StockMovementCommand;
import com.geststock.stock.StockMovementType;
import com.geststock.stock.StockService;
import com.geststock.stock.StockSourceType;
import com.geststock.support.BusinessScenario;
import com.geststock.support.PostgresTestConfiguration;

/**
 * T-C3-8 : débit des ventes avec et sans publication des événements (interrupteur), même jeu de données, en
 * tours alternés après un échauffement. Mesure seulement : les chiffres bruts sont écrits sur la sortie standard,
 * aucun seuil n'est imposé (critère du plan : surcoût ≤ 10 % sur des ventes de 10 lignes). Lancement explicite :
 * {@code ./mvnw verify -Dit.test=AvailabilityThroughputIT -Dperf=true}, et {@code -Dperf.lines=10} pour le nombre de
 * lignes par vente (3 par défaut). Chaque ligne d'une vente porte sur un produit distinct.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Tag("perf")
@EnabledIfSystemProperty(named = "perf", matches = "true")
class AvailabilityThroughputIT {

	private static final int SALES_PER_RUN = 200;

	/** Lignes par vente : {@code -Dperf.lines}, 3 par défaut. */
	private static final int LINES_PER_SALE = Integer.getInteger("perf.lines", 3);

	/** Produits préparés : assez pour que chaque ligne d'une vente porte sur un produit distinct. */
	private static final int PRODUCTS = Math.max(12, LINES_PER_SALE);

	private static final int ROUNDS = 6;

	@Autowired
	private IntegrationEvents events;

	@Autowired
	private SaleService sales;

	@Autowired
	private StockService stock;

	@Autowired
	private CashService cash;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	private StockFixtures fixtures;

	private BusinessScenario scenario;

	private UUID seller;

	private UUID store;

	private final List<UUID> products = new ArrayList<>();

	private IntegrationProperties original;

	@BeforeEach
	void setUp() {
		// G13 : la publication exige une liaison active (drapeau de l'entreprise) ; ce test publie sans liaison,
		// comme au lot 1 : le drapeau est levé pour la durée du test.
		this.jdbc.update("update integration_publication set enabled = true");
		this.fixtures = new StockFixtures(this.jdbc, this.transactions);
		this.scenario = new BusinessScenario(this.jdbc, this.transactions);
		this.seller = this.fixtures.user("debit");
		this.store = this.fixtures.warehouse("debit");
		assertThat(LINES_PER_SALE).as("perf.lines").isPositive();
		for (int i = 0; i < PRODUCTS; i++) {
			UUID product = this.fixtures.product("debit-" + i, 1000);
			as(this.seller, () -> this.stock.applyMovement(StockMovementCommand.of(this.store, product,
					StockMovementType.RECEIPT, 1_000_000, StockSourceType.MANUAL, "Stock de mesure")));
			this.products.add(product);
		}
		as(this.seller, () -> this.cash.open(new OpenCashSessionRequest(new BigDecimal("50000"), null, null), this.store));
		this.original = (IntegrationProperties) ReflectionTestUtils.getField(target(), "properties");
	}

	@AfterEach
	void tearDown() {
		this.jdbc.update("update integration_publication set enabled = false");
		ReflectionTestUtils.setField(target(), "properties", this.original);
		this.scenario.purge(List.of(this.store), List.of(), List.of());
		this.fixtures.purge();
		this.jdbc.update("delete from integration_outbox");
	}

	@Test
	void salesThroughputWithAndWithoutPublication() {
		run(false, 50);
		run(true, 50);
		List<Long> off = new ArrayList<>();
		List<Long> on = new ArrayList<>();
		for (int round = 0; round < ROUNDS; round++) {
			boolean publishFirst = round % 2 == 1;
			(publishFirst ? on : off).add(run(publishFirst, SALES_PER_RUN));
			(publishFirst ? off : on).add(run(!publishFirst, SALES_PER_RUN));
		}

		long medianOff = median(off);
		long medianOn = median(on);
		double overhead = (medianOn - medianOff) * 100.0 / medianOff;
		System.out.printf("T-C3-8 ventes par tour=%d, lignes par vente=%d, tours=%d%n", SALES_PER_RUN, LINES_PER_SALE,
				ROUNDS);
		System.out.printf("T-C3-8 sans publication (ms) : %s ; médiane %d ms ; %.1f ventes/s%n", off, medianOff,
				SALES_PER_RUN * 1000.0 / medianOff);
		System.out.printf("T-C3-8 avec publication (ms) : %s ; médiane %d ms ; %.1f ventes/s%n", on, medianOn,
				SALES_PER_RUN * 1000.0 / medianOn);
		System.out.printf("T-C3-8 surcoût médian : %.1f %%%n", overhead);
		assertThat(off).hasSize(ROUNDS);
	}

	/** Un tour de ventes, interrupteur positionné ; vérifie le nombre d'événements écrits. Durée en ms. */
	private long run(boolean publish, int count) {
		ReflectionTestUtils.setField(target(), "properties",
				new IntegrationProperties(new IntegrationProperties.Publish(publish, DataSize.ofKilobytes(16))));
		this.jdbc.update("delete from integration_outbox");
		long start = System.nanoTime();
		for (int i = 0; i < count; i++) {
			List<CreateSaleRequest.Item> items = new ArrayList<>();
			for (int line = 0; line < LINES_PER_SALE; line++) {
				items.add(new CreateSaleRequest.Item(this.products.get((i * LINES_PER_SALE + line) % this.products.size()),
						1, null));
			}
			as(this.seller, () -> this.sales.create(new CreateSaleRequest(null, null, null, PaymentMethod.CASH, null, null,
					items), this.store));
		}
		long elapsed = (System.nanoTime() - start) / 1_000_000;
		int written = this.jdbc.queryForObject("select count(*) from integration_outbox where type = 'AVAILABILITY'",
				Integer.class);
		assertThat(written).isEqualTo(publish ? count * LINES_PER_SALE : 0);
		return elapsed;
	}

	private IntegrationEvents target() {
		return AopTestUtils.getUltimateTargetObject(this.events);
	}

	private static long median(List<Long> values) {
		List<Long> sorted = values.stream().sorted().toList();
		int middle = sorted.size() / 2;
		return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
	}

}
