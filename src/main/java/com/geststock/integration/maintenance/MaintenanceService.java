package com.geststock.integration.maintenance;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.geststock.integration.AckFloor;
import com.geststock.integration.FeedMaintenance;
import com.geststock.integration.FeedMeasures;
import com.geststock.integration.IntegrationPurgeProperties;
import com.geststock.integration.PassControl;
import com.geststock.integration.PassStop;
import com.geststock.integration.PurgeReport;
import com.geststock.integration.SequencingOutcome;
import com.geststock.integration.link.LinkStatus;
import com.geststock.integration.link.RetainedLink;
import com.geststock.integration.link.RetainedLinks;
import com.geststock.integration.maintenance.MaintenanceAlerts.Alert;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;
import com.geststock.tenancy.TenantSchemaRegistry;

/**
 * Passage de maintenance de l'intégration (lot 3, G20a et G20b ; plan § 33.3) : bail pris (sinon
 * {@link MaintenanceInProgressException}), entreprises actives visitées dans l'ordre de {@link MaintenanceCompanies},
 * bail libéré même en cas d'erreur, rapport agrégé.
 *
 * <p>Pour chaque entreprise, tant que le budget global le permet (G20-D3) : bail prolongé (sauf pour la première, il
 * vient d'être pris) ; tentative commencée ; base en migration : sautée ; aucune liaison active ou suspendue :
 * séquencement des événements sans {@code seq} (G20-D9) ; purge dans le mode configuré (simulation par défaut,
 * G20-D5) ; mesures et alertes (G20c) ; tentative terminée avec son résultat. Une entreprise dispose au plus de la
 * moitié du budget restant, sauf la dernière à visiter (décision D).
 *
 * <p>Mesures et alertes (G20c ; G20-D7, D15, décisions C1 à C8) : liaisons actives ou suspendues lues dans la base
 * plateforme, mesures du flux dans celle de l'entreprise, chacune calculant ses propres âges ; prises même si la purge
 * s'est arrêtée faute de budget (quelques requêtes), jamais après la perte du bail ; règles de
 * {@link MaintenanceAlerts}. Le rapport regroupe les alertes par type et gravité, sans identifiant ; chaque alerte est
 * journalisée (WARN) avec ses identifiants techniques, la génération et les mesures, jamais un message d'exception.
 *
 * <p>Budget épuisé et bail perdu ne sont jamais confondus. Budget épuisé : arrêt propre, l'entreprise en cours finit
 * {@code PARTIAL}, les suivantes sont comptées dans {@code remaining}. Bail perdu (vérifié avant chaque lot et prolongé
 * entre deux entreprises) : aucun nouveau lot, l'entreprise en cours n'est pas terminée (le passage suivant la verra
 * interrompue), aucune entreprise de plus, statut {@code FAILED} ; libération et prolongation sont sans effet sur le
 * bail d'un passage plus récent (génération et propriétaire). Une erreur d'entreprise n'arrête pas les suivantes ; seule
 * sa classe est journalisée, jamais son message.
 */
@Service
class MaintenanceService {

	private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

	private final MaintenanceLease lease;

	private final MaintenanceCompanies companies;

	private final MaintenanceTenants tenants;

	private final MaintenanceTicker ticker;

	private final MaintenanceAuthFailures failures;

	private final IntegrationMaintenanceProperties properties;

	private final IntegrationPurgeProperties purge;

	private final FeedMaintenance feed;

	private final RetainedLinks retainedLinks;

	private final TenantSchemaRegistry schemas;

	private final MaintenanceAlerts alerts;

	MaintenanceService(MaintenanceLease lease, MaintenanceCompanies companies, MaintenanceTenants tenants,
			MaintenanceTicker ticker, MaintenanceAuthFailures failures, IntegrationMaintenanceProperties properties,
			IntegrationPurgeProperties purge, FeedMaintenance feed, RetainedLinks retainedLinks, TenantSchemaRegistry schemas,
			MaintenanceAlerts alerts) {
		this.lease = lease;
		this.companies = companies;
		this.tenants = tenants;
		this.ticker = ticker;
		this.failures = failures;
		this.properties = properties;
		this.purge = purge;
		this.feed = feed;
		this.retainedLinks = retainedLinks;
		this.schemas = schemas;
		this.alerts = alerts;
	}

	MaintenanceReport run() {
		long started = System.nanoTime();
		long deadline = this.ticker.nanoTime() + this.properties.budget().toNanos();
		MaintenanceLease.Lease held = this.lease.acquire(this.properties.leaseTtl())
			.orElseThrow(MaintenanceInProgressException::new);
		Pass pass = new Pass(held, deadline);
		try {
			pass.visitCompanies();
		}
		catch (RuntimeException ex) {
			pass.failed = true;
			log.warn("Maintenance : passage {} interrompu par une erreur générale ({}).", held.generation(),
					ex.getClass().getSimpleName());
		}
		finally {
			if (!this.lease.release(held)) {
				log.warn("Maintenance : bail du passage {} déjà échu ou repris à la libération.", held.generation());
			}
		}
		long durationMs = (System.nanoTime() - started) / 1_000_000;
		String status = pass.failed || pass.leaseLost ? "FAILED"
				: pass.remaining() > 0 || pass.partial ? "PARTIAL" : "COMPLETED";
		List<MaintenanceReport.AlertCount> alertCounts = pass.alertCounts();
		log.info("Maintenance : passage {} {} en {} ms ; entreprises {}/{} visitées, {} sautée(s), {} en erreur ; "
				+ "purge {} : {} supprimé(s) sur {} éligible(s), {} bloquée(s) ; {} alerte(s).", held.generation(), status,
				durationMs, pass.visited, pass.active, pass.skippedUpgrade, pass.errors, this.purge.mode(), pass.deleted,
				pass.eligible, pass.blocked, alertCounts.stream().mapToInt(MaintenanceReport.AlertCount::count).sum());
		return new MaintenanceReport(status, this.purge.mode().name(), held.generation(), durationMs,
				new MaintenanceReport.Companies(pass.active, pass.visited, pass.skippedUpgrade, pass.errors, pass.interrupted,
						pass.remaining()),
				new MaintenanceReport.Purge(pass.eligible, pass.deleted, pass.batches, pass.blocked, pass.maxLockWaitMs),
				new MaintenanceReport.Links(pass.linksActive, pass.linksSuspended), alertCounts,
				new MaintenanceReport.AuthFailures(this.failures.drain(), "INSTANCE"));
	}

	/** Un passage : ses entreprises, ses compteurs, son bail. */
	private final class Pass {

		private final MaintenanceLease.Lease held;

		private final long deadline;

		private int active;

		private int visited;

		private int skippedUpgrade;

		private int errors;

		private int interrupted;

		private long eligible;

		private long deleted;

		private long batches;

		private int blocked;

		private long maxLockWaitMs;

		private int linksActive;

		private int linksSuspended;

		private final Map<Alert, Integer> raised = new HashMap<>();

		private boolean partial;

		private boolean leaseLost;

		private boolean failed;

		Pass(MaintenanceLease.Lease held, long deadline) {
			this.held = held;
			this.deadline = deadline;
		}

		int remaining() {
			return this.active - this.visited;
		}

		/** Alertes regroupées par type puis gravité, dans l'ordre des types (§ 33.4). */
		List<MaintenanceReport.AlertCount> alertCounts() {
			return this.raised.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.comparing(Alert::type).thenComparing(Alert::severity)))
				.map(entry -> new MaintenanceReport.AlertCount(entry.getKey().type().name(),
						entry.getKey().severity().name(), entry.getValue()))
				.toList();
		}

		/** Alerte comptée pour le rapport et journalisée avec ses identifiants techniques, jamais dans le rapport. */
		private void raise(Alert alert, UUID companyId, UUID linkId, String measures) {
			this.raised.merge(alert, 1, Integer::sum);
			log.warn("Maintenance : alerte {} {} ; entreprise {}{} ; passage {} ; {}.", alert.type(), alert.severity(),
					companyId, linkId != null ? ", liaison " + linkId : "", this.held.generation(), measures);
		}

		void visitCompanies() {
			List<TenantRef> activeTenants = MaintenanceService.this.tenants.active();
			List<MaintenanceCompanies.CompanyTurn> turns = MaintenanceService.this.companies.order(activeTenants);
			this.active = turns.size();
			this.interrupted = (int) turns.stream().filter(turn -> turn.interruptions() > 0).count();
			for (MaintenanceCompanies.CompanyTurn turn : turns) {
				if (turn.repeatedlyInterrupted()) {
					raise(MaintenanceAlerts.COMPANY_INTERRUPTED, turn.company().companyId(), null,
							turn.interruptions() + " interruptions consécutives");
				}
			}
			for (int index = 0; index < turns.size(); index++) {
				long now = MaintenanceService.this.ticker.nanoTime();
				if (now >= this.deadline) {
					return;
				}
				// Bail prolongé avant chaque entreprise suivante ; refusé : bail perdu ou échu.
				if (index > 0 && !MaintenanceService.this.lease.extend(this.held, MaintenanceService.this.properties.leaseTtl())) {
					this.leaseLost = true;
					return;
				}
				boolean last = index == turns.size() - 1;
				long companyDeadline = last ? this.deadline : now + (this.deadline - now) / 2;
				if (!visit(turns.get(index).company(), companyDeadline)) {
					return;
				}
			}
		}

		/** @return faux si le bail a été perdu (le passage s'arrête) */
		private boolean visit(TenantRef company, long companyDeadline) {
			UUID companyId = company.companyId();
			this.visited++;
			MaintenanceService.this.companies.markStarted(companyId, this.held.generation());
			MaintenanceCompanies.Outcome outcome;
			try {
				outcome = work(company, control(companyDeadline));
			}
			catch (LeaseLost ex) {
				this.leaseLost = true;
				log.warn("Maintenance : bail du passage {} perdu pendant l'entreprise {} ; arrêt avant tout nouveau lot.",
						this.held.generation(), companyId);
				return false;
			}
			catch (RuntimeException ex) {
				outcome = MaintenanceCompanies.Outcome.ERROR;
				this.errors++;
				log.warn("Maintenance : entreprise {} en erreur ({}) ; les suivantes sont traitées.", companyId,
						ex.getClass().getSimpleName());
				raise(MaintenanceAlerts.COMPANY_ERROR, companyId, null, "erreur " + ex.getClass().getSimpleName());
			}
			if (outcome == MaintenanceCompanies.Outcome.PARTIAL) {
				this.partial = true;
			}
			MaintenanceService.this.companies.markCompleted(companyId, this.held.generation(), outcome);
			return true;
		}

		private MaintenanceCompanies.Outcome work(TenantRef company, PassControl control) {
			if (MaintenanceService.this.schemas.blockingState(company.companyId()).isPresent()) {
				this.skippedUpgrade++;
				return MaintenanceCompanies.Outcome.SKIPPED_UPGRADE;
			}
			return TenantContext.callAs(company, () -> {
				MaintenanceCompanies.Outcome outcome = clean(company, control);
				// C3 : mesures même après un arrêt faute de budget ; jamais après la perte du bail (exception remontée).
				measure(company.companyId());
				return outcome;
			});
		}

		/** Séquencement sans liaison (G20-D9), puis purge, dans le contexte de l'entreprise. */
		private MaintenanceCompanies.Outcome clean(TenantRef company, PassControl control) {
			// G20-D9 : sans liaison active ou suspendue, séquencer avant la purge (qui lit ensuite son plafond P).
			if (MaintenanceService.this.retainedLinks.of(company.companyId()).isEmpty()) {
				SequencingOutcome sequencing = MaintenanceService.this.feed.sequence(control);
				if (sequencing.stop() == PassStop.LEASE_LOST) {
					throw new LeaseLost();
				}
				if (sequencing.stop() == PassStop.LIMIT_REACHED) {
					// Budget épuisé sur un lot plein : du travail reste, pas une erreur.
					return MaintenanceCompanies.Outcome.PARTIAL;
				}
			}
			PurgeReport report = MaintenanceService.this.feed.purge(control);
			this.eligible += report.eligibleCount();
			this.deleted += report.deletedCount();
			this.batches += report.batches();
			this.maxLockWaitMs = Math.max(this.maxLockWaitMs, report.maxLockWaitMs());
			if (report.floor() instanceof AckFloor.Blocked) {
				this.blocked++;
			}
			MaintenanceService.this.alerts.lockWait(report.maxLockWaitMs()).ifPresent(alert -> raise(alert,
					company.companyId(), null, "attente du verrou " + report.maxLockWaitMs() + " ms"));
			if (report.stop() == PassStop.LEASE_LOST) {
				throw new LeaseLost();
			}
			return report.stop() == PassStop.LIMIT_REACHED ? MaintenanceCompanies.Outcome.PARTIAL
					: MaintenanceCompanies.Outcome.OK;
		}

		/** Mesures et alertes de l'entreprise courante (G20c) : liaisons, puis flux ; une alerte par liaison au plus. */
		private void measure(UUID companyId) {
			List<RetainedLink> links = MaintenanceService.this.retainedLinks.states(companyId);
			Set<UUID> ids = links.stream().map(RetainedLink::id).collect(Collectors.toSet());
			FeedMeasures measures = MaintenanceService.this.feed.measure(ids);
			boolean activeLink = false;
			for (RetainedLink link : links) {
				if (link.status() == LinkStatus.ACTIVE) {
					this.linksActive++;
					activeLink = true;
				}
				else {
					this.linksSuspended++;
				}
				FeedMeasures.LinkFeedMeasures linkMeasures = measures.links().get(link.id());
				MaintenanceService.this.alerts.link(link, linkMeasures)
					.ifPresent(alert -> raise(alert, companyId, link.id(), describe(link, linkMeasures)));
			}
			MaintenanceService.this.alerts.unsequenced(activeLink, measures).ifPresent(alert -> raise(alert, companyId, null,
					measures.unsequenced() + " événement(s) sans seq, le plus ancien depuis "
							+ measures.oldestUnsequencedSeconds() + " s"));
		}

		/** Bail vérifié dans la base plateforme ; budget de l'entreprise sur l'horloge monotone. */
		private PassControl control(long companyDeadline) {
			return new PassControl() {

				@Override
				public boolean leaseHeld() {
					return MaintenanceService.this.lease.isHeld(Pass.this.held);
				}

				@Override
				public boolean timeLeft() {
					return MaintenanceService.this.ticker.nanoTime() < companyDeadline;
				}

			};
		}

	}

	/** Mesures d'une liaison pour les journaux : nombres et durées seulement. */
	private static String describe(RetainedLink link, FeedMeasures.LinkFeedMeasures measures) {
		if (link.status() == LinkStatus.SUSPENDED) {
			return "suspendue depuis " + link.sinceSuspensionSeconds() + " s";
		}
		if (measures == null) {
			return "aucun acquittement, activée depuis " + link.sinceActivationSeconds() + " s";
		}
		return "acquittement " + measures.acknowledgedSeq() + " il y a " + measures.sinceAcknowledgedSeconds() + " s, "
				+ measures.pending() + " événement(s) en attente, le plus ancien depuis " + measures.oldestPendingSeconds()
				+ " s, retard " + measures.lag();
	}

	/** Bail perdu pendant une entreprise : remonte jusqu'au passage, jamais compté comme une erreur d'entreprise. */
	private static final class LeaseLost extends RuntimeException {

		LeaseLost() {
			super("Bail de la maintenance perdu", null, false, false);
		}

	}

	/** Bail tenu par un autre passage : {@code 409 MAINTENANCE_IN_PROGRESS}, sans travail. */
	static final class MaintenanceInProgressException extends RuntimeException {

		MaintenanceInProgressException() {
			super("Passage de maintenance déjà en cours", null, false, false);
		}

	}

}
