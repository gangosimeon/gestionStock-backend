package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.integration.AckFloor;
import com.geststock.integration.AckSource;
import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;

/**
 * Limite des acquittements de la purge (lot 3, étape G17 ; plan § 30.4, v17-D4, v12-D4) : liaisons actives ou
 * suspendues de l'entreprise courante, puis leurs acquittements. Aucune liaison : pas de limite ; une liaison sans
 * acquittement : purge bloquée ; sinon le plus petit acquittement. Liaisons révoquées ignorées.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class LinkAckSourceIT {

	private static final String FEED_STATE_ID = "00000000-0000-0000-0000-000000000001";

	private static final Set<String> SCOPES = Set.of("catalog:read");

	@Autowired
	private AckSource acknowledgements;

	@Autowired
	private LinkAcknowledgements store;

	@Autowired
	private LinkRegistry registry;

	@Autowired
	private PlatformDb platform;

	@Autowired
	private JdbcTemplate jdbc;

	private TestLinks links;

	private TenantRef company;

	@BeforeEach
	void setUp() {
		this.links = new TestLinks(this.platform, this.registry);
		this.company = new TenantRef(this.links.legacyCompany(), "test");
		reset();
		// Aucune autre liaison active ou suspendue de l'entreprise de test (laissée par un autre test) ne doit compter.
		assertThat(this.platform.jdbc().sql("""
				select count(*) from platform.integration_link
				where company_id = :c and status in ('ACTIVE', 'SUSPENDED')""").param("c", this.company.companyId())
			.query(Integer.class).single()).isZero();
	}

	@AfterEach
	void tearDown() {
		this.links.cleanUp();
		reset();
	}

	@Test
	void withoutAnyActiveOrSuspendedLinkThereIsNoAcknowledgementLimit() {
		Link revoked = this.links.active(this.links.client(), this.company.companyId(), "shop-g17-r", SCOPES);
		this.registry.revoke(revoked.id(), RevocationReason.SHOP_REQUEST);

		assertThat(floor()).isEqualTo(AckFloor.noLink());
	}

	@Test
	void aLinkWithoutAcknowledgementBlocksThePurge() {
		Link acked = this.links.active(this.links.client(), this.company.companyId(), "shop-g17-a", SCOPES);
		this.links.active(this.links.client(), this.company.companyId(), "shop-g17-b", SCOPES);
		acknowledge(acked, 3);

		assertThat(floor()).isEqualTo(AckFloor.blocked());
	}

	@Test
	void theSmallestAcknowledgementOfActiveAndSuspendedLinksWinsAndARevokedLinkIsIgnored() {
		Link first = this.links.active(this.links.client(), this.company.companyId(), "shop-g17-1", SCOPES);
		Link suspended = this.links.active(this.links.client(), this.company.companyId(), "shop-g17-2", SCOPES);
		Link revoked = this.links.active(this.links.client(), this.company.companyId(), "shop-g17-3", SCOPES);
		acknowledge(first, 7);
		acknowledge(suspended, 4);
		acknowledge(revoked, 1);
		this.registry.suspend(suspended.id(), SuspensionReason.MANUAL);
		this.registry.revoke(revoked.id(), RevocationReason.SHOP_REQUEST);

		assertThat(floor()).as("suspendue comptée, révoquée ignorée").isEqualTo(AckFloor.acknowledged(4));
	}

	@Test
	void theCurrentCompanyIsRequired() {
		assertThatThrownBy(() -> this.acknowledgements.floor()).isInstanceOf(IllegalStateException.class);
	}

	private AckFloor floor() {
		return TenantContext.callAs(this.company, this.acknowledgements::floor);
	}

	private void acknowledge(Link link, long seq) {
		TenantContext.runAs(this.company, () -> this.store.acknowledge(link.id(), seq));
	}

	/** Flux de dix événements séquencés (last_seq = 10) et aucun acquittement. */
	private void reset() {
		this.jdbc.update("delete from integration_link_ack");
		this.jdbc.update("""
				insert into integration_feed_state (id, last_seq, purged_through_seq) values (?::uuid, 10, 0)
				on conflict (id) do update set last_seq = 10, purged_through_seq = 0""", FEED_STATE_ID);
	}

}
