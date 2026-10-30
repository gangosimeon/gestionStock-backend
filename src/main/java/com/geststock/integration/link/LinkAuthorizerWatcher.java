package com.geststock.integration.link;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.geststock.common.security.AccessRightsChangedEvent;
import com.geststock.common.security.ActorWarehouseScope;
import com.geststock.common.security.Permissions;
import com.geststock.integration.LinkWarehouses;
import com.geststock.integration.LinkWarehouses.WarehouseChoice;
import com.geststock.role.RoleService;
import com.geststock.tenancy.CurrentTenant;
import com.geststock.tenancy.TenantContext;
import com.geststock.tenancy.TenantRef;
import com.geststock.user.UserAccount;
import com.geststock.user.UserAccountService;
import com.geststock.user.event.UserDeactivatedEvent;
import com.geststock.warehouse.WarehouseAccess;

/**
 * Suspension {@code AUTHORIZER_LOST_ACCESS} (lot 2, étape G4b ; v11-D8, v14-D10). La personne responsable d'une
 * liaison ({@code authorized_by}) garde ses droits tant que son compte est actif, qu'elle détient
 * {@code integration:manage} (par ses rôles, même calcul que la chaîne de sécurité) et qu'elle a accès à chacun des
 * dépôts de la liaison ({@link WarehouseAccess} : affectations, dépôt par défaut, {@code warehouse:all} ; sans
 * affectation, le dépôt principal). Sinon, ses liaisons actives sont suspendues : le périmètre ne change jamais en
 * silence ; une personne {@code integration:manage} la réactive en reconfirmant les dépôts (G10, G4a).
 *
 * <p><b>Déclencheurs</b>, après le commit du changement seulement (annulé : rien) : désactivation ou suppression d'un
 * utilisateur ({@link UserDeactivatedEvent}) ; rôles ou dépôts d'un utilisateur, permissions d'un rôle, dépôt principal
 * ({@link AccessRightsChangedEvent}). Réévaluation impossible (base plateforme indisponible…) : le changement de droits
 * reste validé, un WARN nomme l'entreprise, aucune erreur pour l'utilisateur ; le rattrapage relève de la tâche
 * planifiée du lot 3.
 */
@Component
class LinkAuthorizerWatcher {

	private static final Logger log = LoggerFactory.getLogger(LinkAuthorizerWatcher.class);

	/** Marqueur : accès à tous les dépôts ({@code warehouse:all}). */
	private static final UUID ALL_WAREHOUSES = new UUID(0, 0);

	private final LinkRegistry links;

	private final LinkAudit audit;

	private final LinkWarehouses warehouses;

	private final UserAccountService accounts;

	private final RoleService roles;

	private final WarehouseAccess access;

	private final CurrentTenant tenant;

	private final TransactionTemplate tenantRead;

	LinkAuthorizerWatcher(LinkRegistry links, LinkAudit audit, LinkWarehouses warehouses, UserAccountService accounts,
			RoleService roles, WarehouseAccess access, CurrentTenant tenant, PlatformTransactionManager transactionManager) {
		this.links = links;
		this.audit = audit;
		this.warehouses = warehouses;
		this.accounts = accounts;
		this.roles = roles;
		this.access = access;
		this.tenant = tenant;
		// Après le commit, les ressources de la transaction terminée sont encore liées au thread : lecture dans une
		// transaction propre.
		this.tenantRead = new TransactionTemplate(transactionManager);
		this.tenantRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.tenantRead.setReadOnly(true);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAccessRightsChanged(AccessRightsChangedEvent event) {
		reassessAfterCommit(event.change());
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onUserDeactivated(UserDeactivatedEvent event) {
		reassessAfterCommit("désactivation d'un utilisateur");
	}

	private void reassessAfterCommit(String change) {
		TenantRef company = this.tenant.require();
		try {
			reassess(company);
		}
		catch (RuntimeException ex) {
			log.warn("Réévaluation des liaisons boutique impossible pour l'entreprise {} après « {} » ({}) : changement de "
					+ "droits conservé ; rattrapage par la tâche planifiée du lot 3.", company.companyId(), change,
					ex.getClass().getSimpleName());
		}
	}

	/**
	 * Suspend chaque liaison active de l'entreprise dont la personne responsable n'a plus ses droits ; une liaison
	 * suspendue ou révoquée n'est pas touchée. Idempotente : une seconde réévaluation ne change rien.
	 * @return nombre de liaisons suspendues
	 */
	int reassess(TenantRef company) {
		List<Link> active = this.links.listForCompany(company.companyId()).stream()
			.filter(link -> link.status() == LinkStatus.ACTIVE)
			.toList();
		if (active.isEmpty()) {
			return 0;
		}
		Set<UUID> lost = TenantContext.callAs(company, () -> this.tenantRead.execute(status -> lost(active)));
		int suspended = 0;
		for (Link link : active) {
			if (!lost.contains(link.id())) {
				continue;
			}
			try {
				this.audit.unit(() -> {
					Link changed = this.links.suspend(link.id(), SuspensionReason.AUTHORIZER_LOST_ACCESS);
					this.audit.record(LinkAudit.Entry.of(LinkAudit.SUSPENDED, changed, LinkAudit.Actor.system("réévaluation des droits"))
						.detail("suspensionReason", SuspensionReason.AUTHORIZER_LOST_ACCESS.name())
						.detail("responsible", link.authorizedBy().toString()));
					return changed;
				});
				suspended++;
				log.info("Liaison {} suspendue (AUTHORIZER_LOST_ACCESS) : sa personne responsable {} n'a plus les droits "
						+ "requis.", link.id(), link.authorizedBy());
			}
			catch (LinkException ex) {
				// Déjà suspendue ou révoquée entre-temps : rien à faire.
				if (ex.reason() != LinkException.Reason.INVALID_TRANSITION) {
					throw ex;
				}
			}
		}
		return suspended;
	}

	/** Liaisons dont la personne responsable a perdu un droit (lu dans la base de l'entreprise). */
	private Set<UUID> lost(List<Link> active) {
		Map<UUID, List<WarehouseChoice>> scopes = this.warehouses.ofLinks(active.stream().map(Link::id).toList());
		Map<UUID, Optional<Set<UUID>>> rights = new HashMap<>();
		Set<UUID> lost = new HashSet<>();
		for (Link link : active) {
			Optional<Set<UUID>> accessible = rights.computeIfAbsent(link.authorizedBy(), this::accessibleWarehouses);
			Set<UUID> required = scopes.getOrDefault(link.id(), List.of()).stream().map(WarehouseChoice::id)
				.collect(Collectors.toSet());
			boolean keeps = accessible.isPresent()
					&& (accessible.get().contains(ALL_WAREHOUSES) || accessible.get().containsAll(required));
			if (!keeps) {
				lost.add(link.id());
			}
		}
		return lost;
	}

	/**
	 * Dépôts accessibles à la personne si elle a encore un compte actif et {@code integration:manage} ; vide sinon
	 * (aucun droit). Contient {@link #ALL_WAREHOUSES} si elle a {@code warehouse:all}.
	 */
	private Optional<Set<UUID>> accessibleWarehouses(UUID userId) {
		Optional<UserAccount> account = this.accounts.findById(userId).filter(UserAccount::active);
		if (account.isEmpty()) {
			return Optional.empty();
		}
		Set<String> permissions = this.roles.permissionsOf(account.get().roles());
		if (!permissions.contains(Permissions.INTEGRATION_MANAGE)) {
			return Optional.empty();
		}
		Set<UUID> ids = this.access.accessibleIds(new ActorWarehouseScope(permissions.contains(Permissions.WAREHOUSE_ALL),
				account.get().defaultWarehouseId(), account.get().warehouseIds()));
		return Optional.of(ids == null ? Set.of(ALL_WAREHOUSES) : Set.copyOf(ids));
	}

}
