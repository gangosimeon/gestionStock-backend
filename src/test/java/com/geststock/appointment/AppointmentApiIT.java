package com.geststock.appointment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rendez-vous (V028) de bout en bout : client du fichier ou prospect, créneau unique par dépôt,
 * horaire futur, clôture (honoré, absent, annulé avec motif), immuabilité d'un rendez-vous clos,
 * filtres et droits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@Transactional
class AppointmentApiIT {

	private static final ZoneId ZONE = ZoneId.of("Africa/Abidjan");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	private UUID agent;

	/** Après-demain 10:30, heure locale du dépôt. */
	private LocalDateTime slot;

	@BeforeEach
	void setUp() {
		this.agent = this.jdbc.queryForObject(
				"insert into app_user (username, last_name) values ('appointment_agent', 'Accueil') returning id",
				UUID.class);
		this.slot = LocalDate.now(ZONE).plusDays(2).atTime(10, 30);
	}

	@Test
	void aProspectBooksAFreeSlotInTheFuture() throws Exception {
		create(prospect("Mamadou Diallo", "77 123 45 67", this.slot))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerId").doesNotExist())
			.andExpect(jsonPath("$.customerName").value("Mamadou Diallo"))
			.andExpect(jsonPath("$.phone").value("77 123 45 67"))
			.andExpect(jsonPath("$.dateTime").value(startsWith(this.slot.toString())))
			.andExpect(jsonPath("$.status").value("SCHEDULED"))
			.andExpect(jsonPath("$.warehouseId").isString())
			.andExpect(jsonPath("$.createdByUserId").value(this.agent.toString()));

		create(prospect("Autre", "770000000", this.slot))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_SLOT_TAKEN"));
		create(prospect("Passé", "770000000", LocalDateTime.now(ZONE).minusHours(1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_IN_PAST"));
		create("{\"customerName\": \"Sans téléphone\", \"dateTime\": \"%s\"}".formatted(this.slot.plusHours(1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_CONTACT_REQUIRED"));
		create(prospect("Lettres", "appelez-moi", this.slot.plusHours(1))).andExpect(status().isBadRequest());
	}

	@Test
	void aCustomerFromTheFileLendsTheirNameAndPhone() throws Exception {
		UUID customer = this.jdbc.queryForObject(
				"insert into customer (code, name, phone) values ('CLI-RDV', 'Awa Diop', '76 555 44 33') returning id",
				UUID.class);
		UUID withoutPhone = this.jdbc.queryForObject(
				"insert into customer (code, name) values ('CLI-RDV2', 'Sans Numéro') returning id", UUID.class);

		create("{\"customerId\": \"%s\", \"customerName\": \"ignoré\", \"dateTime\": \"%s\"}".formatted(customer, this.slot))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.customerId").value(customer.toString()))
			.andExpect(jsonPath("$.customerName").value("Awa Diop"))
			.andExpect(jsonPath("$.phone").value("76 555 44 33"));
		create("{\"customerId\": \"%s\", \"dateTime\": \"%s\"}".formatted(withoutPhone, this.slot.plusHours(1)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_CONTACT_REQUIRED"));
		create("{\"customerId\": \"%s\", \"phone\": \"70 111 22 33\", \"dateTime\": \"%s\"}".formatted(withoutPhone,
				this.slot.plusHours(1)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.phone").value("70 111 22 33"));

		this.mvc.perform(get("/api/appointments").param("customerId", customer.toString()).with(agent()))
			.andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void aScheduledAppointmentIsRescheduledThenCancelledWhichFreesItsSlot() throws Exception {
		String id = idOf(create(prospect("Fatou Ndiaye", "770000001", this.slot)));
		String other = idOf(create(prospect("Moussa", "770000002", this.slot.plusHours(2))));

		// Le nouvel horaire doit être libre et futur ; garder le même horaire reste permis.
		update(id, prospect("Fatou Ndiaye", "770000001", this.slot.plusHours(2)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_SLOT_TAKEN"));
		update(id, prospect("Fatou N.", "770000009", this.slot))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customerName").value("Fatou N."));
		update(id, prospect("Fatou N.", "770000009", this.slot.plusDays(1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dateTime").value(startsWith(this.slot.plusDays(1).toString())));

		// Pas encore le jour : ni honoré ni absent. Annulation : motif obligatoire.
		close(other, "{\"status\": \"COMPLETED\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_YET_DUE"));
		close(other, "{\"status\": \"CANCELLED\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_CANCELLATION_REASON_REQUIRED"));
		close(other, "{\"status\": \"CANCELLED\", \"reason\": \"Client en voyage\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancellationReason").value("Client en voyage"))
			.andExpect(jsonPath("$.closedAt").isString())
			.andExpect(jsonPath("$.closedByUserId").value(this.agent.toString()));

		// Clos : figé. Son créneau est libéré.
		update(other, prospect("Moussa", "770000002", this.slot.plusHours(3)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_CLOSED"));
		close(other, "{\"status\": \"NO_SHOW\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_CLOSED"));
		create(prospect("Remplaçant", "770000003", this.slot.plusHours(2))).andExpect(status().isCreated());
	}

	@Test
	void aDueAppointmentIsMarkedCompletedOrNoShow() throws Exception {
		UUID warehouse = this.jdbc.queryForObject("select id from warehouse where main", UUID.class);
		UUID earlier = dueAppointment(warehouse, "Venu ce matin");
		UUID missed = dueAppointment(warehouse, "Jamais venu");

		close(earlier.toString(), "{\"status\": \"COMPLETED\", \"reason\": \"ignoré\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("COMPLETED"))
			.andExpect(jsonPath("$.cancellationReason").doesNotExist());
		close(missed.toString(), "{\"status\": \"NO_SHOW\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("NO_SHOW"));
		close(missed.toString(), "{\"status\": \"SCHEDULED\"}").andExpect(status().isConflict());

		this.mvc.perform(get("/api/appointments").param("status", "NO_SHOW").with(agent()))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].customerName").value("Jamais venu"));
		assertThat(this.jdbc.queryForObject("""
				select count(*) from audit_log where entity_type = 'APPOINTMENT' and module = 'APPOINTMENT'
				and action in ('COMPLETE', 'NO_SHOW') and status = 'SUCCESS'""", Integer.class)).isEqualTo(2);
	}

	@Test
	void theListIsTheAgendaOfTheCurrentWarehouse() throws Exception {
		create(prospect("Demain", "771111111", this.slot.minusDays(1)));
		create(prospect("Après-demain", "772222222", this.slot));
		create(prospect("Plus tard", "773333333", this.slot.plusDays(5)));

		this.mvc.perform(get("/api/appointments").param("page", "0").param("size", "10").with(agent()))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[0].customerName").value("Demain"))
			.andExpect(jsonPath("$.items[2].customerName").value("Plus tard"));
		String day = this.slot.toLocalDate().toString();
		this.mvc.perform(get("/api/appointments").param("dateFrom", day).param("dateTo", day).with(agent()))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].customerName").value("Après-demain"));
		this.mvc.perform(get("/api/appointments").param("search", "77 333").with(agent()))
			.andExpect(jsonPath("$.total").value(1));
		this.mvc.perform(get("/api/appointments").param("search", "demain").with(agent()))
			.andExpect(jsonPath("$.total").value(2));
	}

	@Test
	void readingAndManagingNeedTheirPermissionsAndNothingIsDeleted() throws Exception {
		String id = idOf(create(prospect("Client", "770000000", this.slot)));
		this.mvc.perform(get("/api/appointments/{id}", id).with(actor(Permissions.APPOINTMENT_READ)))
			.andExpect(status().isOk());
		this.mvc.perform(post("/api/appointments").with(actor(Permissions.APPOINTMENT_READ))
			.contentType(MediaType.APPLICATION_JSON)
			.content(prospect("X", "770", this.slot.plusHours(1)))).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/appointments").with(actor(Permissions.SALE_CREATE))).andExpect(status().isForbidden());
		this.mvc.perform(delete("/api/appointments/{id}", id).with(agent())).andExpect(status().isMethodNotAllowed());
		this.mvc.perform(get("/api/appointments/{id}", UUID.randomUUID()).with(agent()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("APPOINTMENT_NOT_FOUND"));
		assertThatThrownBy(() -> this.jdbc.update("delete from appointment where id = ?::uuid", id))
			.isInstanceOf(DataAccessException.class);
	}

	@Test
	void theAgendaOfAllWarehousesNeedsWarehouseAll() throws Exception {
		create(prospect("Ici", "770000001", this.slot)).andExpect(status().isCreated());
		UUID elsewhere = this.jdbc.queryForObject(
				"insert into warehouse (code, name) values ('RDV-AIL', 'Rendez-vous ailleurs') returning id", UUID.class);
		this.jdbc.update("""
				insert into appointment (warehouse_id, customer_name, phone, scheduled_at, created_by)
				values (?, 'Ailleurs', '770000002', now() + interval '3 days', ?)""", elsewhere, this.agent);

		this.mvc.perform(get("/api/appointments").with(agent()))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].customerName").value("Ici"));
		this.mvc.perform(get("/api/appointments").param("allWarehouses", "true").with(agent()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2));
		this.mvc.perform(get("/api/appointments").param("allWarehouses", "true")
			.with(actor(Permissions.APPOINTMENT_READ)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WAREHOUSE_ACCESS_DENIED"));
	}

	@Test
	void aClosedAppointmentIsFrozenInTheDatabase() throws Exception {
		String id = idOf(create(prospect("Client", "770000000", this.slot)));
		close(id, "{\"status\": \"CANCELLED\", \"reason\": \"Erreur\"}").andExpect(status().isOk());
		assertThatThrownBy(() -> this.jdbc.update("update appointment set note = 'x' where id = ?::uuid", id))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ne se modifie plus");
	}

	// --- Outils -------------------------------------------------------------------------------

	/** Rendez-vous déjà échu (une heure plus tôt), inséré directement : l'API refuse le passé. */
	private UUID dueAppointment(UUID warehouse, String name) {
		return this.jdbc.queryForObject("""
				insert into appointment (warehouse_id, customer_name, phone, scheduled_at, created_by)
				values (?, ?, '770000000', now() - (random() * interval '50 minutes') - interval '5 minutes', ?)
				returning id""", UUID.class, warehouse, name, this.agent);
	}

	private static String prospect(String name, String phone, LocalDateTime at) {
		return "{\"customerName\": \"%s\", \"phone\": \"%s\", \"dateTime\": \"%s\", \"note\": \"RDV\"}".formatted(name,
				phone, at);
	}

	private ResultActions create(String body) throws Exception {
		return this.mvc.perform(post("/api/appointments").with(agent()).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions update(String id, String body) throws Exception {
		return this.mvc.perform(put("/api/appointments/{id}", id).with(agent()).contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions close(String id, String body) throws Exception {
		return this.mvc.perform(patch("/api/appointments/{id}/status", id).with(agent())
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private RequestPostProcessor agent() {
		return actor(Permissions.ALL.toArray(String[]::new));
	}

	private RequestPostProcessor actor(String... permissions) {
		return authentication(StockFixtures.authentication(this.agent, permissions));
	}

	private String idOf(ResultActions result) throws Exception {
		return this.json.readTree(result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
			.get("id")
			.asString();
	}

}
