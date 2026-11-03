package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantRef;

/**
 * État conservé par entreprise et ordre de passage de la maintenance (G20-D13, plan § 33.3) : une entreprise jamais
 * visitée passe d'abord, puis la tentative la plus ancienne ; une entreprise interrompue <b>une</b> fois reprend en
 * tête au passage suivant ; interrompue deux fois de suite, elle rejoint la file normale et est signalée ; une fin de
 * traitement, quel que soit son résultat, remet le compteur à zéro ; seul le passage qui a commencé une entreprise peut
 * la terminer (génération). Entreprises de test créées dans la base plateforme, supprimées avec leur état (cascade).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class MaintenanceCompaniesIT {

	@Autowired
	private MaintenanceCompanies companies;

	@Autowired
	private PlatformDb platform;

	private final List<UUID> created = new ArrayList<>();

	private TenantRef a;

	private TenantRef b;

	private TenantRef c;

	private long generation = 1_000_000;

	@BeforeEach
	void createCompanies() {
		this.a = company();
		this.b = company();
		this.c = company();
	}

	@AfterEach
	void deleteCompanies() {
		for (UUID id : this.created) {
			this.platform.jdbc().sql("delete from platform.company where id = :id").param("id", id).update();
		}
		assertThat(this.platform.jdbc().sql("select count(*) from platform.integration_maintenance_company where company_id = any(:ids)")
			.param("ids", this.created.toArray(UUID[]::new)).query(Integer.class).single())
			.as("état supprimé avec l'entreprise").isZero();
		this.created.clear();
	}

	@Test
	void neverVisitedCompaniesComeFirstThenTheOldestAttempt() {
		assertThat(order()).containsExactly(this.a, this.b, this.c);

		visit(this.a, MaintenanceCompanies.Outcome.OK);
		assertThat(order()).containsExactly(this.b, this.c, this.a);
		visit(this.b, MaintenanceCompanies.Outcome.PARTIAL);
		assertThat(order()).containsExactly(this.c, this.a, this.b);
	}

	@Test
	void withABudgetForOneCompanyEachCompanyIsVisitedInThreePasses() {
		Map<TenantRef, Integer> visits = new HashMap<>();
		for (int pass = 0; pass < 3; pass++) {
			TenantRef first = order().get(0);
			visit(first, MaintenanceCompanies.Outcome.PARTIAL);
			visits.merge(first, 1, Integer::sum);
		}
		assertThat(visits).containsOnlyKeys(this.a, this.b, this.c).allSatisfy((company, count) -> assertThat(count).isEqualTo(1));
	}

	@Test
	void aCompanyInErrorDoesNotBlockTheOthers() {
		visitAll();
		visit(this.a, MaintenanceCompanies.Outcome.ERROR);
		assertThat(order()).as("entreprise en erreur en fin de file").containsExactly(this.b, this.c, this.a);
		assertThat(turns()).allSatisfy(turn -> assertThat(turn.interruptions()).isZero());
	}

	@Test
	void aCompanyInterruptedOnceResumesFirstAtTheNextPass() {
		visitAll();
		// A commence le dernier, et le passage s'arrête (délai HTTP, instance arrêtée) avant sa fin.
		long interrupted = start(this.a);

		List<MaintenanceCompanies.CompanyTurn> turns = turns();
		assertThat(turns).extracting(MaintenanceCompanies.CompanyTurn::company).containsExactly(this.a, this.b, this.c);
		assertThat(turns.get(0).resumed()).as("reprise rapide").isTrue();
		assertThat(turns.get(0).interruptions()).isEqualTo(1);
		assertThat(turns.get(0).repeatedlyInterrupted()).isFalse();

		// L'ancien passage ne peut plus terminer A une fois qu'un nouveau l'a commencée.
		long resumed = start(this.a);
		assertThat(this.companies.markCompleted(this.a.companyId(), interrupted, MaintenanceCompanies.Outcome.OK))
			.as("fin écrite par l'ancien passage").isFalse();
		assertThat(this.companies.markCompleted(this.a.companyId(), resumed, MaintenanceCompanies.Outcome.OK)).isTrue();
		assertThat(turns()).allSatisfy(turn -> {
			assertThat(turn.interruptions()).as("compteur remis à zéro par une fin").isZero();
			assertThat(turn.resumed()).isFalse();
		});
		assertThat(order()).containsExactly(this.b, this.c, this.a);
	}

	@Test
	void aCompanyInterruptedTwiceInARowJoinsTheNormalQueueAndIsReported() {
		visitAll();
		start(this.a);
		// Reprise rapide, de nouveau interrompue : elle ne monopolise plus la tête de file.
		assertThat(order().get(0)).isEqualTo(this.a);
		start(this.a);

		List<MaintenanceCompanies.CompanyTurn> turns = turns();
		assertThat(turns).extracting(MaintenanceCompanies.CompanyTurn::company).containsExactly(this.b, this.c, this.a);
		MaintenanceCompanies.CompanyTurn turnOfA = turns.get(2);
		assertThat(turnOfA.resumed()).isFalse();
		assertThat(turnOfA.interruptions()).isEqualTo(2);
		assertThat(turnOfA.repeatedlyInterrupted()).as("signalée (COMPANY_INTERRUPTED)").isTrue();

		// Une fin, même en erreur, remet le compteur à zéro.
		long last = start(this.a);
		assertThat(this.companies.markCompleted(this.a.companyId(), last, MaintenanceCompanies.Outcome.ERROR)).isTrue();
		assertThat(turns()).allSatisfy(turn -> assertThat(turn.interruptions()).isZero());
	}

	@Test
	void onlyTheActiveCompaniesPassedInAreOrdered() {
		visitAll();
		assertThat(this.companies.order(List.of(this.c, this.a))).extracting(MaintenanceCompanies.CompanyTurn::company)
			.containsExactly(this.a, this.c);
		assertThat(this.companies.order(List.of())).isEmpty();
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	private List<TenantRef> order() {
		return turns().stream().map(MaintenanceCompanies.CompanyTurn::company).toList();
	}

	private List<MaintenanceCompanies.CompanyTurn> turns() {
		return this.companies.order(List.of(this.a, this.b, this.c));
	}

	private void visitAll() {
		visit(this.a, MaintenanceCompanies.Outcome.OK);
		visit(this.b, MaintenanceCompanies.Outcome.OK);
		visit(this.c, MaintenanceCompanies.Outcome.OK);
	}

	private void visit(TenantRef company, MaintenanceCompanies.Outcome outcome) {
		long started = start(company);
		assertThat(this.companies.markCompleted(company.companyId(), started, outcome)).isTrue();
	}

	private long start(TenantRef company) {
		long current = ++this.generation;
		this.companies.markStarted(company.companyId(), current);
		return current;
	}

	/** Entreprise ACTIVE de la base plateforme, créée dans l'ordre des numéros (ordre d'entrée de {@code order}). */
	private TenantRef company() {
		String code = "it-g20-" + UUID.randomUUID().toString().substring(0, 8);
		UUID id = this.platform.jdbc().sql("""
				insert into platform.company (code, legal_name, trade_name, owner_last_name, admin_username, status)
				values (:code, 'Entreprise G20', 'Entreprise G20', 'Test', 'admin', 'ACTIVE') returning id""")
			.param("code", code).query(UUID.class).single();
		this.created.add(id);
		return new TenantRef(id, code);
	}

}
