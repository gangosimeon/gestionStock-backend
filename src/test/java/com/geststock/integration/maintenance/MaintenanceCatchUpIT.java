package com.geststock.integration.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.geststock.integration.link.Authorizer;
import com.geststock.integration.link.Link;
import com.geststock.tenancy.TenantRef;

import tools.jackson.databind.JsonNode;

/**
 * Rattrapages du lot 2 par la maintenance (lot 3, G20f-1), à travers la route, sur l'entreprise historique :
 *
 * <ul>
 * <li>drapeau de publication baissé alors qu'une liaison est active ou suspendue (des événements ont pu ne pas être
 * publiés) : levé, alerte {@code PUBLICATION_FLAG_RAISED} ({@code CRITICAL}) ; la maintenance ne le baisse jamais
 * (un drapeau levé à tort n'écrit que des événements en trop, séquencés puis purgés) ;</li>
 * <li>personne responsable qui a perdu ses droits sans que la réévaluation ait eu lieu : liaison suspendue
 * ({@code AUTHORIZER_LOST_ACCESS}), alerte {@code LINK_SUSPENDED_LOST_ACCESS} ({@code WARNING}) ; idempotent ;
 * réversible par une réactivation, rien n'est supprimé.</li>
 * </ul>
 */
class MaintenanceCatchUpIT extends DeleteModeMaintenanceSupport {

	private TenantRef company;

	private UUID client;

	@BeforeEach
	void setUp() {
		this.company = legacy();
		resetLegacyFeed();
		this.client = createClient();
		forgetMaintenanceState(List.of(this.company.companyId()));
		doReturn(List.of(this.company)).when(this.tenants).active();
		setFlag(false);
	}

	@AfterEach
	void tearDown() {
		deleteClient(this.client);
		setFlag(false);
		resetLegacyFeed();
	}

	// --- Drapeau de publication ---------------------------------------------------------------------------------

	@Test
	void aFlagLoweredWhileALinkIsActiveIsRaisedOnceWithACriticalAlert() throws Exception {
		activeLink(this.client, this.company, "shop-g20f-drapeau");
		setFlag(false);

		JsonNode first = pass();
		assertThat(flag()).as("drapeau levé par la maintenance").isTrue();
		assertThat(alerts(first)).contains("PUBLICATION_FLAG_RAISED/CRITICAL/1");

		assertThat(alerts(pass())).as("rien à rattraper ensuite").noneMatch(alert -> alert.startsWith("PUBLICATION_FLAG_RAISED"));
		assertThat(flag()).isTrue();
	}

	@Test
	void theMaintenanceNeverLowersTheFlag() throws Exception {
		setFlag(true);
		JsonNode report = pass();
		assertThat(flag()).as("aucune liaison, mais jamais baissé par la maintenance").isTrue();
		assertThat(alerts(report)).noneMatch(alert -> alert.startsWith("PUBLICATION_FLAG_RAISED"));
	}

	// --- Réévaluation de la personne responsable -------------------------------------------------------------

	@Test
	void aLinkWhoseResponsibleLostAccessIsSuspendedOnceByTheCatchUp() throws Exception {
		Link link = activeLink(this.client, this.company, "shop-g20f-responsable-parti",
				new Authorizer(UUID.randomUUID(), "ancien-admin"));

		JsonNode first = pass();
		assertThat(state(link)).isEqualTo("SUSPENDED/AUTHORIZER_LOST_ACCESS");
		assertThat(alerts(first)).contains("LINK_SUSPENDED_LOST_ACCESS/WARNING/1");
		assertThat(first.get("links").get("suspended").asInt()).as("mesures prises après la réévaluation").isEqualTo(1);
		assertThat(systemSuspensions(link)).as("trace d'audit de la réévaluation").isEqualTo(1);

		JsonNode second = pass();
		assertThat(alerts(second)).as("idempotent").noneMatch(alert -> alert.startsWith("LINK_SUSPENDED_LOST_ACCESS"));
		assertThat(systemSuspensions(link)).isEqualTo(1);
	}

	@Test
	void aLinkWhoseResponsibleKeepsAccessStaysActive() throws Exception {
		Link link = activeLink(this.client, this.company, "shop-g20f-responsable-present");
		JsonNode report = pass();
		assertThat(state(link)).isEqualTo("ACTIVE/null");
		assertThat(alerts(report)).noneMatch(alert -> alert.startsWith("LINK_SUSPENDED_LOST_ACCESS"));
	}

	// --- Outils --------------------------------------------------------------------------------------------------

	private boolean flag() {
		return Boolean.TRUE.equals(this.jdbc.queryForObject("select enabled from integration_publication", Boolean.class));
	}

	private void setFlag(boolean value) {
		this.jdbc.update("update integration_publication set enabled = ?", value);
	}

	/** « STATUT/motif de suspension ». */
	private String state(Link link) {
		return this.platform.jdbc()
			.sql("select status || '/' || coalesce(suspension_reason, 'null') from platform.integration_link where id = :id")
			.param("id", link.id()).query(String.class).single();
	}

	private int systemSuspensions(Link link) {
		return this.platform.jdbc().sql("""
				select count(*) from platform.integration_audit_log
				where link_id = :id and action = 'LINK_SUSPENDED' and actor_type = 'SYSTEM'
				  and details ->> 'suspensionReason' = 'AUTHORIZER_LOST_ACCESS'""").param("id", link.id()).query(Integer.class).single();
	}

	private static List<String> alerts(JsonNode report) {
		List<String> alerts = new ArrayList<>();
		report.get("alerts").forEach(alert -> alerts.add(alert.get("type").asString() + "/" + alert.get("severity").asString()
				+ "/" + alert.get("count").asInt()));
		return alerts;
	}

}
