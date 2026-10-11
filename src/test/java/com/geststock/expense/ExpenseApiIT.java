package com.geststock.expense;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.geststock.common.security.Permissions;
import com.geststock.stock.StockFixtures;
import com.geststock.support.PostgresTestConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dépenses (V027) de bout en bout : enregistrement hors caisse et en espèces, annulation motivée
 * avec retour en caisse, totaux, immuabilité en base, droits. Les contraintes différées sont
 * forcées avant le rollback de chaque test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class ExpenseApiIT {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID manager;

	@BeforeEach
	void setUp() {
		this.manager = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('expense_mgr', 'Comptable') returning id", UUID.class);
	}

	@Test
	void aNonCashExpenseIsRecordedOutsideTheCashRegister() throws Exception {
		create("""
				{"category": "RENT", "label": "Loyer d'octobre", "amount": 250000, "expenseDateIso": "%s",
				 "paymentMethod": "BANK_TRANSFER", "externalReference": "VIR-778", "note": "Bail 2026"}"""
			.formatted(today()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.number").value(matchesPattern("DEP-\\d{4}-\\d{6}")))
			.andExpect(jsonPath("$.category").value("RENT"))
			.andExpect(jsonPath("$.label").value("Loyer d'octobre"))
			.andExpect(jsonPath("$.amount").value(250000))
			.andExpect(jsonPath("$.expenseDateIso").value(today().toString()))
			.andExpect(jsonPath("$.paymentMethod").value("BANK_TRANSFER"))
			.andExpect(jsonPath("$.status").value("PAID"))
			.andExpect(jsonPath("$.warehouseId").isString())
			.andExpect(jsonPath("$.createdByUserId").value(this.manager.toString()));
		assertThat(this.jdbc.queryForObject("select count(*) from cash_entry where source_type = 'EXPENSE'",
				Integer.class)).isZero();

		// Date future, catégorie inconnue, montant nul : refusés.
		create(expense("TRANSPORT", "10", "MOBILE_MONEY", today().plusDays(1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("EXPENSE_DATE_IN_FUTURE"));
		create("{\"category\": \"PARTY\", \"label\": \"x\", \"amount\": 10, \"paymentMethod\": \"CHEQUE\"}")
			.andExpect(status().isBadRequest());
		create(expense("TRANSPORT", "0", "CHEQUE", today())).andExpect(status().isBadRequest());
		checkDeferredConstraints();
	}

	@Test
	void aCashExpenseLeavesMyCashRegisterAndComesBackWhenCancelled() throws Exception {
		// Sans caisse ouverte : refusé, rien n'est enregistré.
		create(expense("TRANSPORT", "15000", "CASH", today()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_OPEN_CASH_SESSION"));
		assertThat(this.jdbc.queryForObject("select count(*) from expense where created_by = ?", Integer.class,
				this.manager)).isZero();

		this.mvc.perform(post("/api/cash-register/open").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"openingBalance\": 20000}")).andExpect(status().isCreated());
		create(expense("TRANSPORT", "30000", "CASH", today()))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CASH_INSUFFICIENT"));
		// Espèces par défaut.
		String id = idOf(create("{\"category\": \"TRANSPORT\", \"label\": \"Taxi livraison\", \"amount\": 15000}"));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from cash_entry where entry_type = 'EXPENSE' and direction = 'OUT'
				and source_type = 'EXPENSE' and source_id = ?::uuid and amount = 15000""", Integer.class, id)).isOne();
		this.mvc.perform(get("/api/cash-register/current").with(manager()))
			.andExpect(jsonPath("$.totalOut").value(15000))
			.andExpect(jsonPath("$.expectedCash").value(5000));

		// Annulation : motif obligatoire, l'argent revient, une seule fois.
		cancel(id, "{\"reason\": \" \"}").andExpect(status().isBadRequest());
		cancel(id, "{\"reason\": \"Course payée par le client\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancellationReason").value("Course payée par le client"))
			.andExpect(jsonPath("$.cancelledByUserId").value(this.manager.toString()))
			.andExpect(jsonPath("$.cancelledAt").isString());
		assertThat(this.jdbc.queryForObject("""
				select count(*) from cash_entry where entry_type = 'EXPENSE_CANCELLATION' and direction = 'IN'
				and source_id = ?::uuid and amount = 15000""", Integer.class, id)).isOne();
		this.mvc.perform(get("/api/cash-register/current").with(manager()))
			.andExpect(jsonPath("$.totalOut").value(0))
			.andExpect(jsonPath("$.expectedCash").value(20000));
		cancel(id, "{\"reason\": \"Encore\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EXPENSE_ALREADY_CANCELLED"));
		checkDeferredConstraints();
	}

	@Test
	void totalsCountOnlyPaidExpensesAndFiltersApplyToListAndTotals() throws Exception {
		create(expense("TRANSPORT", "5000", "MOBILE_MONEY", today()));
		create(expense("TRANSPORT", "7000", "BANK_TRANSFER", today().minusDays(3)));
		create(expense("SUPPLIES", "11000", "CHEQUE", today()));
		String cancelled = idOf(create(expense("UTILITIES", "40000", "BANK_TRANSFER", today())));
		cancel(cancelled, "{\"reason\": \"Facture en double\"}").andExpect(status().isOk());

		this.mvc.perform(get("/api/expenses/summary").with(manager()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(23000))
			.andExpect(jsonPath("$.count").value(3))
			.andExpect(jsonPath("$.cashTotal").value(0))
			.andExpect(jsonPath("$.cancelledCount").value(1))
			.andExpect(jsonPath("$.byCategory[0].category").value("TRANSPORT"))
			.andExpect(jsonPath("$.byCategory[0].count").value(2))
			.andExpect(jsonPath("$.byCategory[0].total").value(12000))
			.andExpect(jsonPath("$.byCategory[1].category").value("SUPPLIES"));
		this.mvc.perform(get("/api/expenses/summary").param("dateFrom", today().toString()).with(manager()))
			.andExpect(jsonPath("$.total").value(16000));

		// Liste : la plus récente d'abord, annulées visibles, filtres combinables.
		this.mvc.perform(get("/api/expenses").param("page", "0").param("size", "10").with(manager()))
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.items[3].expenseDateIso").value(today().minusDays(3).toString()));
		this.mvc.perform(get("/api/expenses").param("status", "CANCELLED").with(manager()))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].id").value(cancelled));
		this.mvc.perform(get("/api/expenses").param("category", "TRANSPORT").param("search", "dépense 7000")
			.with(manager())).andExpect(jsonPath("$.total").value(1));
		checkDeferredConstraints();
	}

	// Une erreur SQL interrompt la transaction du test : une vérification par test.
	@Test
	void anExpenseIsFrozenInTheDatabase() throws Exception {
		String id = idOf(create(expense("OTHER", "1000", "CHEQUE", today())));
		assertThatThrownBy(() -> this.jdbc.update("update expense set amount = 2 where id = ?::uuid", id))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ne se modifie pas");
	}

	@Test
	void anExpenseIsNeverDeletedInTheDatabase() throws Exception {
		String id = idOf(create(expense("OTHER", "1000", "CHEQUE", today())));
		assertThatThrownBy(() -> this.jdbc.update("delete from expense where id = ?::uuid", id))
			.isInstanceOf(DataAccessException.class);
	}

	@Test
	void readingRecordingAndCancellingNeedTheirOwnPermissions() throws Exception {
		String id = idOf(create(expense("OTHER", "1000", "CHEQUE", today())));
		this.mvc.perform(get("/api/expenses/{id}", id).with(actor(Permissions.EXPENSE_READ)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.label").value("Dépense 1000"));
		this.mvc.perform(post("/api/expenses").with(actor(Permissions.EXPENSE_READ))
			.contentType(MediaType.APPLICATION_JSON)
			.content(expense("OTHER", "1000", "CHEQUE", today()))).andExpect(status().isForbidden());
		this.mvc.perform(post("/api/expenses/{id}/cancel", id).with(actor(Permissions.EXPENSE_CREATE))
			.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"x\"}")).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/expenses").with(actor(Permissions.SALE_CREATE))).andExpect(status().isForbidden());
		// Pas de suppression : une dépense s'annule.
		this.mvc.perform(delete("/api/expenses/{id}", id).with(manager())).andExpect(status().isMethodNotAllowed());
		this.mvc.perform(get("/api/expenses/{id}", UUID.randomUUID()).with(manager()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("EXPENSE_NOT_FOUND"));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where action = 'CREATE' and entity_type = 'EXPENSE' and module = 'EXPENSE'
				and status = 'SUCCESS' and entity_id = ?""", Integer.class, id)).isOne();
	}

	// --- Outils -------------------------------------------------------------------------------

	private void checkDeferredConstraints() {
		this.jdbc.execute("set constraints all immediate");
	}

	private static LocalDate today() {
		return LocalDate.now(ZoneOffset.UTC);
	}

	private static String expense(String category, String amount, String method, LocalDate date) {
		return """
				{"category": "%s", "label": "Dépense %s", "amount": %s, "paymentMethod": "%s", "expenseDateIso": "%s"}"""
			.formatted(category, amount, amount, method, date);
	}

	private ResultActions create(String body) throws Exception {
		return this.mvc.perform(post("/api/expenses").with(manager()).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions cancel(String id, String body) throws Exception {
		return this.mvc.perform(post("/api/expenses/{id}/cancel", id).with(manager())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private RequestPostProcessor manager() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.manager, permissions));
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

}
