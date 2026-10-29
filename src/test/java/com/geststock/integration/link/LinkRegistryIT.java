package com.geststock.integration.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.geststock.platform.PlatformDb;
import com.geststock.support.PostgresTestConfiguration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Cycle de vie des liaisons (lot 2, étape G3 ; v14-D2, D3, D5, D10, D13, D14 et ajustements v14) sur la base
 * plateforme réelle : création en attente (devise, entreprise, portées, unicité, liaisons expirées), activation et
 * secret, suspension, réactivation, révocation, vérification du secret, transitions refusées sans effet, concurrence
 * (vraies connexions, barrière). Les lignes créées sont supprimées après chaque test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class LinkRegistryIT {

	private static final Authorizer ADMIN = new Authorizer(UUID.randomUUID(), "admin");

	@Autowired
	private LinkRegistry links;

	@Autowired
	private PlatformDb platform;

	private final List<UUID> companies = new ArrayList<>();

	private final List<UUID> clients = new ArrayList<>();

	private ListAppender<ILoggingEvent> logs;

	private Logger root;

	private Level rootLevel;

	@BeforeEach
	void captureLogs() {
		this.root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		this.rootLevel = this.root.getLevel();
		this.root.setLevel(Level.DEBUG);
		this.logs = new ListAppender<>();
		this.logs.start();
		this.root.addAppender(this.logs);
	}

	@AfterEach
	void cleanUp() {
		this.root.detachAppender(this.logs);
		this.root.setLevel(this.rootLevel);
		JdbcClient jdbc = this.platform.jdbc();
		for (UUID client : this.clients) {
			jdbc.sql("""
					delete from platform.integration_authorization_code where request_id in
					  (select id from platform.integration_authorization_request where client_id = :c)""").param("c", client).update();
			jdbc.sql("delete from platform.integration_link where client_id = :c").param("c", client).update();
			jdbc.sql("delete from platform.integration_authorization_request where client_id = :c").param("c", client).update();
			jdbc.sql("delete from platform.integration_client where id = :c").param("c", client).update();
		}
		for (UUID company : this.companies) {
			jdbc.sql("delete from platform.integration_link where company_id = :c").param("c", company).update();
			jdbc.sql("delete from platform.company where id = :c").param("c", company).update();
		}
	}

	// --- Création en attente ----------------------------------------------------------------------------

	@Test
	void aPendingLinkIsCreatedFromAnApprovedRequestWithTheNormalizedCurrency() {
		UUID company = company("ACTIVE", "FCFA");
		UUID client = client("ACTIVE");
		UUID request = request(client, "shop-1", Set.of("catalog:read", "orders:write"), "APPROVED");

		Link link = this.links.createPending(request, company, ADMIN, Set.of("catalog:read"));

		assertThat(link.status()).isEqualTo(LinkStatus.PENDING);
		assertThat(link.currency()).as("FCFA normalisée (v11-D6)").isEqualTo("XOF");
		assertThat(link.companyId()).isEqualTo(company);
		assertThat(link.clientId()).isEqualTo(client);
		assertThat(link.externalShopId()).isEqualTo("shop-1");
		assertThat(link.shopName()).isEqualTo("Boutique shop-1");
		assertThat(link.scopes()).containsExactly("catalog:read");
		assertThat(link.authorizedBy()).isEqualTo(ADMIN.userId());
		assertThat(link.authorizedByUsername()).isEqualTo("admin");
		assertThat(link.activatedAt()).isNull();
		assertThat(this.links.find(link.id())).contains(link);
	}

	@Test
	void anUnknownCurrencyIsRefusedAndNothingIsCreated() {
		UUID client = client("ACTIVE");
		UUID request = request(client, "shop-1", Set.of("catalog:read"), "APPROVED");

		assertRefused(() -> this.links.createPending(request, company("ACTIVE", "EUR"), ADMIN, Set.of("catalog:read")),
				LinkException.Reason.CURRENCY_UNSUPPORTED);
		assertThat(linksOf(client)).isZero();
	}

	@Test
	void aMissingSuspendedOrInactiveCompanyIsRefused() {
		UUID client = client("ACTIVE");
		UUID request = request(client, "shop-1", Set.of("catalog:read"), "APPROVED");

		assertRefused(() -> this.links.createPending(request, UUID.randomUUID(), ADMIN, Set.of("catalog:read")),
				LinkException.Reason.COMPANY_NOT_FOUND);
		for (String status : List.of("SUSPENDED", "PENDING", "PROVISIONING", "FAILED")) {
			assertRefused(() -> this.links.createPending(request, company(status, "XOF"), ADMIN, Set.of("catalog:read")),
					LinkException.Reason.COMPANY_NOT_ACTIVE);
		}
		assertThat(linksOf(client)).isZero();
	}

	@Test
	void scopesMustBeRequestedAndAllowedForTheClient() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		UUID request = request(client, "shop-1", Set.of("catalog:read"), "APPROVED");

		assertRefused(() -> this.links.createPending(request, company, ADMIN, Set.of("orders:write")),
				LinkException.Reason.SCOPE_NOT_REQUESTED);
		assertRefused(() -> this.links.createPending(request, company, ADMIN, Set.of()), LinkException.Reason.INVALID_ARGUMENT);
		UUID tooWide = request(client, "shop-2", Set.of("catalog:read", "price:propose"), "APPROVED");
		assertRefused(() -> this.links.createPending(tooWide, company, ADMIN, Set.of("catalog:read")),
				LinkException.Reason.SCOPE_NOT_ALLOWED);
		assertThat(linksOf(client)).isZero();
	}

	@Test
	void theRequestMustExistBeApprovedAndComeFromAnActiveClient() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");

		assertRefused(() -> this.links.createPending(UUID.randomUUID(), company, ADMIN, Set.of("catalog:read")),
				LinkException.Reason.REQUEST_NOT_FOUND);
		assertRefused(() -> this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), null), company, ADMIN,
				Set.of("catalog:read")), LinkException.Reason.REQUEST_NOT_APPROVED);
		assertRefused(() -> this.links.createPending(request(client, "shop-2", Set.of("catalog:read"), "DENIED"), company,
				ADMIN, Set.of("catalog:read")), LinkException.Reason.REQUEST_NOT_APPROVED);
		UUID disabled = client("DISABLED");
		assertRefused(() -> this.links.createPending(request(disabled, "shop-3", Set.of("catalog:read"), "APPROVED"), company,
				ADMIN, Set.of("catalog:read")), LinkException.Reason.CLIENT_DISABLED);
	}

	// --- Unicité et liaisons en attente expirées ------------------------------------------------------

	@Test
	void aShopWithAPendingLinkWhoseCodeIsStillValidCannotBeLinkedAgain() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		UUID first = request(client, "shop-1", Set.of("catalog:read"), "APPROVED");
		Link pending = this.links.createPending(first, company, ADMIN, Set.of("catalog:read"));
		code(first, pending.id(), "now() + interval '5 minutes'");

		assertRefused(() -> this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company,
				ADMIN, Set.of("catalog:read")), LinkException.Reason.SHOP_ALREADY_LINKED);
		assertThat(this.links.find(pending.id()).orElseThrow().status()).isEqualTo(LinkStatus.PENDING);
		assertThat(linksOf(client)).isEqualTo(1);
	}

	@Test
	void anActiveOrSuspendedLinkBlocksANewOneForTheSameShopAndClient() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		Link active = active(company, client, "shop-1");

		assertRefused(() -> this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company,
				ADMIN, Set.of("catalog:read")), LinkException.Reason.SHOP_ALREADY_LINKED);
		this.links.suspend(active.id(), SuspensionReason.MANUAL);
		assertRefused(() -> this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company,
				ADMIN, Set.of("catalog:read")), LinkException.Reason.SHOP_ALREADY_LINKED);
		this.links.revoke(active.id(), RevocationReason.SHOP_REQUEST);
		assertThat(this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company, ADMIN,
				Set.of("catalog:read")).status()).as("ancienne liaison révoquée : nouvelle liaison acceptée").isEqualTo(LinkStatus.PENDING);
	}

	@Test
	void aPendingLinkWhoseCodeExpiredIsRevokedThenTheNewOneIsCreated() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		UUID first = request(client, "shop-1", Set.of("catalog:read"), "APPROVED");
		Link expired = this.links.createPending(first, company, ADMIN, Set.of("catalog:read"));
		this.platform.jdbc().sql("""
				insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at, created_at)
				values (:hash, :request, :link, now() - interval '1 minute', now() - interval '6 minutes')""")
			.param("hash", randomHash()).param("request", first).param("link", expired.id()).update();

		Link fresh = this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company, ADMIN,
				Set.of("catalog:read"));

		assertThat(fresh.status()).isEqualTo(LinkStatus.PENDING);
		Link revoked = this.links.find(expired.id()).orElseThrow();
		assertThat(revoked.status()).isEqualTo(LinkStatus.REVOKED);
		assertThat(revoked.revocationReason()).isEqualTo(RevocationReason.EXPIRED);
		assertThat(revoked.revokedAt()).isNotNull();
		assertThat(revoked.activatedAt()).isNull();
	}

	@Test
	void aPendingLinkWithoutCodeOlderThanTheCodeLifetimeIsAlsoExpired() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		Link orphan = this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company, ADMIN,
				Set.of("catalog:read"));
		this.platform.jdbc().sql("update platform.integration_link set created_at = now() - interval '6 minutes' where id = :id")
			.param("id", orphan.id()).update();

		this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company, ADMIN, Set.of("catalog:read"));

		assertThat(this.links.find(orphan.id()).orElseThrow().revocationReason()).isEqualTo(RevocationReason.EXPIRED);
	}

	@Test
	void twoSimultaneousRequestsForTheSameShopCreateOnlyOneLink() throws Exception {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		UUID first = request(client, "shop-1", Set.of("catalog:read"), "APPROVED");
		UUID second = request(client, "shop-1", Set.of("catalog:read"), "APPROVED");
		CyclicBarrier barrier = new CyclicBarrier(2);

		List<Object> outcomes = race(
				() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.links.createPending(first, company, ADMIN, Set.of("catalog:read"));
				},
				() -> {
					barrier.await(10, TimeUnit.SECONDS);
					return this.links.createPending(second, company, ADMIN, Set.of("catalog:read"));
				});

		assertThat(outcomes).filteredOn(Link.class::isInstance).hasSize(1);
		assertThat(outcomes).filteredOn(LinkException.class::isInstance).singleElement()
			.satisfies(e -> assertThat(((LinkException) e).reason()).isEqualTo(LinkException.Reason.SHOP_ALREADY_LINKED));
		assertThat(nonRevokedLinksOf(client, "shop-1")).isEqualTo(1);
	}

	// --- Activation, secret ----------------------------------------------------------------------------

	@Test
	void activationReturnsTheSecretOnceAndStoresOnlyItsHash() throws Exception {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		Link pending = this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company, ADMIN,
				Set.of("catalog:read"));
		assertThat(this.links.verifySecret(pending.id(), "n-importe-quoi")).as("PENDING : aucun secret").isFalse();

		ActivatedLink activated = this.links.activate(pending.id());

		String secret = activated.secret();
		assertThat(secret).matches("^[A-Za-z0-9_-]{43}$");
		assertThat(activated.link().status()).isEqualTo(LinkStatus.ACTIVE);
		assertThat(activated.link().activatedAt()).isNotNull();
		assertThat(hashOf(pending.id())).isEqualTo(sha256Hex(secret)).matches("^[0-9a-f]{64}$");
		assertThat(this.links.verifySecret(pending.id(), secret)).isTrue();
		assertThat(this.links.verifySecret(pending.id(), secret + "x")).isFalse();
		assertThat(this.links.verifySecret(pending.id(), null)).isFalse();
		assertThat(this.links.verifySecret(UUID.randomUUID(), secret)).isFalse();
		assertRefused(() -> this.links.activate(pending.id()), LinkException.Reason.INVALID_TRANSITION);
		assertThat(hashOf(pending.id())).as("seconde activation sans effet").isEqualTo(sha256Hex(secret));
	}

	@Test
	void revocationErasesTheHashesAndTheSecretNoLongerVerifies() {
		Link active = active(company("ACTIVE", "XOF"), client("ACTIVE"), "shop-1");
		String secret = this.lastSecret;
		this.platform.jdbc().sql("""
				update platform.integration_link set pending_secret_hash = :h, pending_secret_created_at = now() where id = :id""")
			.param("h", randomHash()).param("id", active.id()).update();

		Link revoked = this.links.revoke(active.id(), RevocationReason.GESTSTOCK_ADMIN);

		assertThat(revoked.status()).isEqualTo(LinkStatus.REVOKED);
		assertThat(revoked.revocationReason()).isEqualTo(RevocationReason.GESTSTOCK_ADMIN);
		assertThat(revoked.revokedAt()).isNotNull();
		Map<String, Object> row = row(active.id());
		assertThat(row.get("secret_hash")).isNull();
		assertThat(row.get("pending_secret_hash")).isNull();
		assertThat(row.get("pending_secret_created_at")).isNull();
		assertThat(this.links.verifySecret(active.id(), secret)).isFalse();
	}

	@Test
	void aSuspendedLinkKeepsItsSecretAndCanBeReactivated() {
		Link active = active(company("ACTIVE", "XOF"), client("ACTIVE"), "shop-1");
		String secret = this.lastSecret;

		Link suspended = this.links.suspend(active.id(), SuspensionReason.AUTHORIZER_LOST_ACCESS);
		assertThat(suspended.status()).isEqualTo(LinkStatus.SUSPENDED);
		assertThat(suspended.suspensionReason()).isEqualTo(SuspensionReason.AUTHORIZER_LOST_ACCESS);
		assertThat(suspended.suspendedAt()).isNotNull();
		assertThat(this.links.verifySecret(active.id(), secret)).isTrue();

		Link reactivated = this.links.reactivate(active.id(), ADMIN);
		assertThat(reactivated.status()).isEqualTo(LinkStatus.ACTIVE);
		assertThat(reactivated.suspensionReason()).isNull();
		assertThat(reactivated.suspendedAt()).as("date de la dernière suspension conservée").isNotNull();
		assertThat(this.links.verifySecret(active.id(), secret)).isTrue();
	}

	/**
	 * v14-D10 : la personne qui réactive reconfirme les dépôts et devient responsable de la liaison. Après une
	 * suspension AUTHORIZER_LOST_ACCESS, la liaison ne référence plus la personne qui a perdu ses droits.
	 */
	@Test
	void reactivationMakesThePersonWhoReactivatesResponsibleAndChangesNothingElse() {
		Link active = active(company("ACTIVE", "XOF"), client("ACTIVE"), "shop-1");
		this.links.suspend(active.id(), SuspensionReason.AUTHORIZER_LOST_ACCESS);
		Authorizer manager = new Authorizer(UUID.randomUUID(), "gerant");

		Map<String, Object> suspended = row(active.id());
		assertThat(suspended.get("authorized_by")).isEqualTo(ADMIN.userId());
		assertUnchangedAfterRefusal(active.id(), id -> this.links.reactivate(id, null), LinkException.Reason.INVALID_ARGUMENT);
		assertUnchangedAfterRefusal(active.id(), id -> this.links.reactivate(id, new Authorizer(null, "gerant")),
				LinkException.Reason.INVALID_ARGUMENT);
		assertUnchangedAfterRefusal(active.id(), id -> this.links.reactivate(id, new Authorizer(UUID.randomUUID(), " ")),
				LinkException.Reason.INVALID_ARGUMENT);

		Link reactivated = this.links.reactivate(active.id(), manager);

		assertThat(reactivated.authorizedBy()).isEqualTo(manager.userId());
		assertThat(reactivated.authorizedByUsername()).isEqualTo("gerant");
		Map<String, Object> after = row(active.id());
		assertThat(after.get("authorized_by")).isEqualTo(manager.userId());
		assertThat(after.get("authorized_by_username")).isEqualTo("gerant");
		assertThat(after.get("status")).isEqualTo("ACTIVE");
		assertThat(after.get("suspension_reason")).isNull();
		assertThat(((java.sql.Timestamp) after.get("updated_at")).toInstant())
			.isAfterOrEqualTo(((java.sql.Timestamp) suspended.get("updated_at")).toInstant());
		Set<String> changed = Set.of("authorized_by", "authorized_by_username", "status", "suspension_reason", "updated_at");
		assertThat(without(after, changed)).as("autres colonnes inchangées").isEqualTo(without(suspended, changed));
	}

	// --- Transitions --------------------------------------------------------------------------------------

	@Test
	void everyRefusedTransitionFailsExplicitlyWithoutChangingTheRow() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		Link pending = this.links.createPending(request(client, "shop-p", Set.of("catalog:read"), "APPROVED"), company, ADMIN,
				Set.of("catalog:read"));
		Link active = active(company, client, "shop-a");
		Link suspended = active(company, client, "shop-s");
		this.links.suspend(suspended.id(), SuspensionReason.MANUAL);
		Link revoked = active(company, client, "shop-r");
		this.links.revoke(revoked.id(), RevocationReason.SHOP_REQUEST);

		assertUnchanged(pending.id(), id -> this.links.suspend(id, SuspensionReason.MANUAL));
		assertUnchanged(pending.id(), id -> this.links.reactivate(id, ADMIN));

		assertUnchanged(active.id(), this.links::activate);
		assertUnchanged(active.id(), id -> this.links.reactivate(id, ADMIN));
		assertUnchanged(active.id(), id -> this.links.revoke(id, RevocationReason.EXPIRED));

		assertUnchanged(suspended.id(), this.links::activate);
		assertUnchanged(suspended.id(), id -> this.links.suspend(id, SuspensionReason.AUTHORIZER_LOST_ACCESS));
		assertUnchanged(suspended.id(), id -> this.links.revoke(id, RevocationReason.EXPIRED));

		assertUnchanged(revoked.id(), this.links::activate);
		assertUnchanged(revoked.id(), id -> this.links.suspend(id, SuspensionReason.MANUAL));
		assertUnchanged(revoked.id(), id -> this.links.reactivate(id, ADMIN));
		assertUnchanged(revoked.id(), id -> this.links.revoke(id, RevocationReason.SHOP_REQUEST));
	}

	@Test
	void everyAllowedTransitionSucceeds() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		Link pendingThenRevoked = this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company,
				ADMIN, Set.of("catalog:read"));
		assertThat(this.links.revoke(pendingThenRevoked.id(), RevocationReason.CODE_REPLAYED).status()).isEqualTo(LinkStatus.REVOKED);
		Link pendingExpired = this.links.createPending(request(client, "shop-2", Set.of("catalog:read"), "APPROVED"), company,
				ADMIN, Set.of("catalog:read"));
		assertThat(this.links.revoke(pendingExpired.id(), RevocationReason.EXPIRED).revocationReason()).isEqualTo(RevocationReason.EXPIRED);
		Link replayed = active(company, client, "shop-3");
		assertThat(this.links.revoke(replayed.id(), RevocationReason.CODE_REPLAYED).status()).isEqualTo(LinkStatus.REVOKED);
		Link suspendedThenRevoked = active(company, client, "shop-4");
		this.links.suspend(suspendedThenRevoked.id(), SuspensionReason.MANUAL);
		Link revoked = this.links.revoke(suspendedThenRevoked.id(), RevocationReason.SHOP_REQUEST);
		assertThat(revoked.status()).isEqualTo(LinkStatus.REVOKED);
		assertThat(revoked.suspensionReason()).as("motif de suspension effacé à la révocation").isNull();
	}

	@Test
	void operationsOnAnUnknownLinkAreRefused() {
		UUID unknown = UUID.randomUUID();
		assertRefused(() -> this.links.activate(unknown), LinkException.Reason.LINK_NOT_FOUND);
		assertRefused(() -> this.links.suspend(unknown, SuspensionReason.MANUAL), LinkException.Reason.LINK_NOT_FOUND);
		assertRefused(() -> this.links.reactivate(unknown, ADMIN), LinkException.Reason.LINK_NOT_FOUND);
		assertRefused(() -> this.links.revoke(unknown, RevocationReason.SHOP_REQUEST), LinkException.Reason.LINK_NOT_FOUND);
		assertThat(this.links.find(unknown)).isEmpty();
	}

	@Test
	void revocationRacingActivationEndsInACoherentRevokedState() throws Exception {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		for (int round = 0; round < 10; round++) {
			Link pending = this.links.createPending(request(client, "shop-race-" + round, Set.of("catalog:read"), "APPROVED"), company,
					ADMIN, Set.of("catalog:read"));
			CyclicBarrier barrier = new CyclicBarrier(2);

			List<Object> outcomes = race(
					() -> {
						barrier.await(10, TimeUnit.SECONDS);
						return this.links.activate(pending.id());
					},
					() -> {
						barrier.await(10, TimeUnit.SECONDS);
						return this.links.revoke(pending.id(), RevocationReason.SHOP_REQUEST);
					});

			assertThat(outcomes.get(1)).as("la révocation réussit toujours").isInstanceOf(Link.class);
			if (outcomes.get(0) instanceof LinkException refused) {
				assertThat(refused.reason()).as("activation après révocation").isEqualTo(LinkException.Reason.INVALID_TRANSITION);
			}
			else {
				assertThat(outcomes.get(0)).as("activation avant révocation").isInstanceOf(ActivatedLink.class);
			}
			Map<String, Object> row = row(pending.id());
			assertThat(row.get("status")).isEqualTo("REVOKED");
			assertThat(row.get("secret_hash")).isNull();
			assertThat(row.get("revocation_reason")).isEqualTo("SHOP_REQUEST");
		}
	}

	// --- Aucun secret dans les traces ---------------------------------------------------------------

	@Test
	void theSecretAppearsInNoLogMessageOrToString() {
		UUID company = company("ACTIVE", "XOF");
		UUID client = client("ACTIVE");
		Link pending = this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company, ADMIN,
				Set.of("catalog:read"));
		ActivatedLink activated = this.links.activate(pending.id());
		String secret = activated.secret();
		String hash = sha256Hex(secret);
		List<String> texts = new ArrayList<>(List.of(activated.toString(), activated.link().toString(),
				this.links.find(pending.id()).orElseThrow().toString()));
		for (Runnable refused : List.<Runnable>of(() -> this.links.activate(pending.id()),
				() -> this.links.reactivate(pending.id(), ADMIN),
				() -> this.links.createPending(request(client, "shop-1", Set.of("catalog:read"), "APPROVED"), company, ADMIN,
						Set.of("catalog:read")))) {
			try {
				refused.run();
			}
			catch (LinkException ex) {
				texts.add(ex.getMessage());
			}
		}
		this.links.verifySecret(pending.id(), secret);
		this.links.revoke(pending.id(), RevocationReason.SHOP_REQUEST);
		this.logs.list.forEach(event -> texts.add(event.getFormattedMessage()));

		assertThat(texts).hasSizeGreaterThan(5)
			.allSatisfy(text -> assertThat(text).doesNotContain(secret, hash));
	}

	// --- Outils -----------------------------------------------------------------------------------------

	private String lastSecret;

	private Link active(UUID company, UUID client, String shop) {
		Link pending = this.links.createPending(request(client, shop, Set.of("catalog:read"), "APPROVED"), company, ADMIN,
				Set.of("catalog:read"));
		ActivatedLink activated = this.links.activate(pending.id());
		this.lastSecret = activated.secret();
		return activated.link();
	}

	private void assertUnchanged(UUID link, Consumer<UUID> operation) {
		Map<String, Object> before = row(link);
		assertThatThrownBy(() -> operation.accept(link)).isInstanceOfSatisfying(LinkException.class,
				e -> assertThat(e.reason()).isEqualTo(LinkException.Reason.INVALID_TRANSITION));
		assertThat(row(link)).as("ligne inchangée").isEqualTo(before);
	}

	private void assertUnchangedAfterRefusal(UUID link, Consumer<UUID> operation, LinkException.Reason reason) {
		Map<String, Object> before = row(link);
		assertThatThrownBy(() -> operation.accept(link)).isInstanceOfSatisfying(LinkException.class,
				e -> assertThat(e.reason()).isEqualTo(reason));
		assertThat(row(link)).as("ligne inchangée").isEqualTo(before);
	}

	private static Map<String, Object> without(Map<String, Object> row, Set<String> columns) {
		Map<String, Object> copy = new java.util.LinkedHashMap<>(row);
		copy.keySet().removeAll(columns);
		return copy;
	}

	private static void assertRefused(Runnable operation, LinkException.Reason reason) {
		assertThatThrownBy(operation::run).as(reason.name())
			.isInstanceOfSatisfying(LinkException.class, e -> assertThat(e.reason()).isEqualTo(reason));
	}

	private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> a = executor.submit(first);
			Future<?> b = executor.submit(second);
			return List.of(outcome(a), outcome(b));
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static Object outcome(Future<?> future) throws Exception {
		try {
			return future.get(30, TimeUnit.SECONDS);
		}
		catch (java.util.concurrent.ExecutionException ex) {
			if (ex.getCause() instanceof LinkException refused) {
				return refused;
			}
			throw ex;
		}
	}

	private UUID company(String status, String currency) {
		UUID id = this.platform.jdbc().sql("""
				insert into platform.company (code, legal_name, trade_name, owner_last_name, admin_username, currency, status)
				values (:code, 'Essai G3', 'Essai G3', 'Essai', 'admin', :currency, :status) returning id""")
			.param("code", "it-g3-" + UUID.randomUUID().toString().substring(0, 8)).param("currency", currency)
			.param("status", status).query(UUID.class).single();
		this.companies.add(id);
		return id;
	}

	private UUID client(String status) {
		UUID id = this.platform.jdbc().sql("""
				insert into platform.integration_client (client_id, name, secret_hash, allowed_scopes, status, disabled_at)
				values (:clientId, 'JeCréeMaBoutique (essai)', :hash, ARRAY['catalog:read','orders:read','orders:write']::varchar[],
				        :status, case when cast(:status as varchar) = 'DISABLED' then now() end) returning id""")
			.param("clientId", "jcmb-g3-" + UUID.randomUUID().toString().substring(0, 8)).param("hash", randomHash())
			.param("status", status).query(UUID.class).single();
		this.platform.jdbc().sql("insert into platform.integration_client_redirect_uri (client_id, redirect_uri) values (:c, :u)")
			.param("c", id).param("u", "https://www.jecreemaboutique.com/integrations/geststock/callback").update();
		this.clients.add(id);
		return id;
	}

	private UUID request(UUID client, String shop, Set<String> scopes, String decision) {
		return this.platform.jdbc().sql("""
				insert into platform.integration_authorization_request (request_uri_hash, client_id, external_shop_id, shop_name,
				  redirect_uri, code_challenge, scopes, state, expires_at, decision, used_at)
				values (:hash, :client, :shop, :name, 'https://www.jecreemaboutique.com/integrations/geststock/callback',
				  'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM', string_to_array(:scopes, ',')::varchar[], repeat('s', 43),
				  now() + interval '10 minutes', cast(:decision as varchar), case when cast(:decision as varchar) is null then null else now() end)
				returning id""")
			.param("hash", randomHash()).param("client", client).param("shop", shop).param("name", "Boutique " + shop)
			.param("scopes", String.join(",", scopes)).param("decision", decision).query(UUID.class).single();
	}

	private void code(UUID request, UUID link, String expiresAt) {
		this.platform.jdbc().sql("insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at) "
				+ "values (:hash, :request, :link, " + expiresAt + ")")
			.param("hash", randomHash()).param("request", request).param("link", link).update();
	}

	private int linksOf(UUID client) {
		return this.platform.jdbc().sql("select count(*) from platform.integration_link where client_id = :c").param("c", client)
			.query(Integer.class).single();
	}

	private int nonRevokedLinksOf(UUID client, String shop) {
		return this.platform.jdbc().sql("""
				select count(*) from platform.integration_link
				where client_id = :c and external_shop_id = :s and status <> 'REVOKED'""")
			.param("c", client).param("s", shop).query(Integer.class).single();
	}

	private String hashOf(UUID link) {
		return this.platform.jdbc().sql("select secret_hash from platform.integration_link where id = :id").param("id", link)
			.query(String.class).single();
	}

	/** Ligne complète ; les tableaux SQL deviennent des listes, comparables par valeur. */
	private Map<String, Object> row(UUID link) {
		Map<String, Object> row = new java.util.LinkedHashMap<>(this.platform.jdbc()
			.sql("select * from platform.integration_link where id = :id").param("id", link).query().singleRow());
		row.replaceAll((column, value) -> {
			try {
				return value instanceof java.sql.Array array ? List.of((Object[]) array.getArray()) : value;
			}
			catch (java.sql.SQLException ex) {
				throw new IllegalStateException(ex);
			}
		});
		return row;
	}

	private static String randomHash() {
		return sha256Hex(UUID.randomUUID().toString());
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
