package com.geststock.integration.link;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.geststock.exception.ErrorCode;
import com.geststock.integration.CurrencyCodes;
import com.geststock.integration.LinkWarehouses;
import com.geststock.integration.LinkWarehouses.WarehouseChoice;
import com.geststock.integration.UnsupportedCurrencyException;
import com.geststock.platform.PlatformDb;

/**
 * Consentement et gestion des liaisons côté GestStock (lot 2, étape G10 ; v14-D1 étapes 2 et 3, D2, D10, D14).
 *
 * <p><b>Demande</b> : retrouvée par l'empreinte SHA-256 de son {@code request_uri}, jamais par la valeur elle-même ;
 * une seule décision ({@code APPROVED} ou {@code DENIED}), prise par une mise à jour conditionnelle (demande non
 * décidée, non expirée) : de deux décisions simultanées, une seule modifie la ligne. Approbation : décision, liaison
 * {@code PENDING} de l'entreprise de la personne connectée, à son nom, et code d'autorisation (256 bits, empreinte
 * seule, 5 minutes) dans une seule transaction de la base plateforme. La redirection est construite ici, depuis la
 * {@code redirect_uri} enregistrée dans la demande, avec {@code state} tel que reçu et {@code iss} (RFC 9207).
 *
 * <p><b>Dépôts vendables (G4a, v11-D8)</b> : choisis à l'approbation, reconfirmés à la réactivation, dans la base de
 * l'entreprise ({@link LinkWarehouses}) ; la liaison vit dans la base plateforme. Deux bases, aucune transaction
 * commune : l'ordre d'écriture garantit qu'un échec à mi-chemin ne donne jamais à une liaison plus de dépôts que ceux
 * approuvés.
 * <ul>
 * <li>Approbation : (1) dépôts écrits dans la base de l'entreprise sous l'identifiant de la future liaison, choisi
 * ici ; (2) décision, liaison et code dans la base plateforme ; échec en (2) : (3) les dépôts écrits en (1) sont
 * retirés. Si (3) échoue aussi, restent des lignes pour un identifiant de liaison qui n'existe pas : aucun jeton ne
 * peut le porter, elles n'exposent rien. À l'inverse, une liaison créée avant ses dépôts existerait, même un instant,
 * sans le périmètre approuvé.</li>
 * <li>Réactivation (liaison suspendue seulement) : (1) liste remplacée dans la base de l'entreprise pendant que la
 * liaison est suspendue ({@code 403 LINK_SUSPENDED}, rien n'est servi), avec, dans la même transaction, un événement
 * {@code AVAILABILITY} par produit stocké dans l'ancienne ou la nouvelle liste si elle change (v11-D9) ;
 * (2) réactivation dans la base plateforme. Échec en (2) : la liaison reste suspendue avec la liste reconfirmée par la
 * personne connectée, jamais plus large ; une nouvelle réactivation la remplace ; les événements publiés font
 * seulement recalculer la même valeur.</li>
 * </ul>
 *
 * <p><b>Liaisons</b> : celles de l'entreprise courante seulement ; une liaison d'une autre entreprise reçoit la même
 * réponse qu'une liaison inexistante. Journaux : identifiants seulement, jamais de {@code request_uri} ni de code.
 */
@Service
class LinkConsentService {

	private static final Logger log = LoggerFactory.getLogger(LinkConsentService.class);

	private static final int CODE_BYTES = 32;

	private static final String REQUEST = """
			select r.external_shop_id, r.shop_name, r.scopes, r.expires_at, r.decision, r.expires_at > now() as live,
			       c.client_id, c.name as client_name
			from platform.integration_authorization_request r
			join platform.integration_client c on c.id = r.client_id
			where r.request_uri_hash = :hash""";

	/** Décision unique : seule une demande non décidée et non expirée est modifiée. */
	private static final String DECIDE = """
			update platform.integration_authorization_request set decision = :decision, used_at = now()
			where request_uri_hash = :hash and decision is null and expires_at > now()
			returning id, client_id, external_shop_id, shop_name, redirect_uri, state, scopes""";

	private static final String REQUEST_STATE = """
			select decision, expires_at > now() as live from platform.integration_authorization_request
			where request_uri_hash = :hash""";

	private static final String INSERT_CODE = """
			insert into platform.integration_authorization_code (code_hash, request_id, link_id, expires_at)
			values (:hash, :request, :link, now() + interval '""" + LinkRegistry.CODE_LIFETIME + "')";

	private static final String COMPANY_CURRENCY = "select currency from platform.company where id = :id";

	private final PlatformDb platform;

	private final LinkRegistry links;

	private final LinkWarehouses warehouses;

	private final LinkAudit audit;

	private final SecureRandom random = new SecureRandom();

	LinkConsentService(PlatformDb platform, LinkRegistry links, LinkWarehouses warehouses, LinkAudit audit) {
		this.platform = platform;
		this.audit = audit;
		this.links = links;
		this.warehouses = warehouses;
	}

	// --- Demandes d'autorisation ----------------------------------------------------------------------------

	/** Demande à afficher sur l'écran de consentement ; refusée si elle ne peut plus être approuvée. */
	RequestView lookup(String requestUri, UUID companyId) {
		Pending request = this.platform.jdbc().sql(REQUEST).param("hash", LinkRegistry.sha256Hex(requestUri))
			.query((rs, n) -> new Pending(new RequestView(rs.getString("client_id"), rs.getString("client_name"),
					rs.getString("external_shop_id"), rs.getString("shop_name"), sorted(rs.getArray("scopes")),
					rs.getObject("expires_at", OffsetDateTime.class).toInstant(), null, List.of()),
					rs.getString("decision") != null,
					rs.getBoolean("live")))
			.optional()
			.orElseThrow(() -> new LinkConsentException(ErrorCode.AUTHORIZATION_REQUEST_NOT_FOUND));
		if (request.decided()) {
			throw new LinkConsentException(ErrorCode.AUTHORIZATION_REQUEST_ALREADY_DECIDED);
		}
		if (!request.live()) {
			throw new LinkConsentException(ErrorCode.AUTHORIZATION_REQUEST_EXPIRED);
		}
		return request.view().with(currency(companyId), this.warehouses.accessible());
	}

	/**
	 * Approbation : décision, liaison en attente et code, dans une seule transaction (tout ou rien).
	 * @return URL de redirection vers la boutique, avec le code (montrée au navigateur, jamais journalisée)
	 */
	String approve(String requestUri, Set<String> grantedScopes, Set<UUID> warehouseIds, UUID companyId,
			Authorizer authorizer) {
		this.warehouses.requireAccessible(warehouseIds);
		UUID linkId = UUID.randomUUID();
		// (1) Base de l'entreprise : dépôts de la future liaison.
		this.warehouses.replace(linkId, warehouseIds, authorizer.userId());
		try {
			return decideAndCreate(requestUri, grantedScopes, warehouseIds, companyId, authorizer, linkId);
		}
		catch (RuntimeException ex) {
			// (3) Compensation : la liaison n'existe pas, ses dépôts sont retirés (inoffensifs s'ils restaient).
			try {
				this.warehouses.remove(linkId);
			}
			catch (RuntimeException cleanup) {
				log.warn("Dépôts de la liaison {} non retirés après un refus d'approbation ({}) : liaison inexistante, sans effet.",
						linkId, cleanup.getClass().getSimpleName());
			}
			throw ex;
		}
	}

	/** (2) Base plateforme : décision, liaison en attente sous l'identifiant choisi, code ; tout ou rien. */
	private String decideAndCreate(String requestUri, Set<String> grantedScopes, Set<UUID> warehouseIds, UUID companyId,
			Authorizer authorizer, UUID linkId) {
		try {
			return this.audit.unit(() -> {
				Decided request = decide(requestUri, "APPROVED");
				if (!request.scopes().containsAll(grantedScopes)) {
					throw new LinkConsentException(ErrorCode.LINK_SCOPE_NOT_REQUESTED);
				}
				Link link = this.links.createPending(linkId, request.id(), companyId, authorizer, grantedScopes);
				byte[] raw = new byte[CODE_BYTES];
				this.random.nextBytes(raw);
				String code = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
				Arrays.fill(raw, (byte) 0);
				this.platform.jdbc().sql(INSERT_CODE).param("hash", LinkRegistry.sha256Hex(code)).param("request", request.id())
					.param("link", link.id()).update();
				this.audit.record(LinkAudit.Entry.of(LinkAudit.APPROVED, link, LinkAudit.Actor.user(authorizer))
					.detail("scopes", grantedScopes).detail("warehouses", warehouseIds));
				log.info("Demande d'autorisation {} approuvée : liaison {} en attente (entreprise {}).", request.id(), link.id(),
						companyId);
				return redirect(request, "code", code);
			});
		}
		catch (LinkException ex) {
			throw LinkConsentException.of(ex);
		}
	}

	/** Refus : décision seulement ; aucune liaison, aucun code. */
	String deny(String requestUri, UUID companyId, Authorizer authorizer) {
		return this.audit.unit(() -> {
			Decided request = decide(requestUri, "DENIED");
			this.audit.record(new LinkAudit.Entry(LinkAudit.DENIED, LinkAudit.Severity.INFO, companyId, null, request.clientId(),
					null, request.shopId(), request.shopName(), LinkAudit.Actor.user(authorizer), null, null,
					new java.util.LinkedHashMap<>()));
			log.info("Demande d'autorisation {} refusée.", request.id());
			return redirect(request, "error", "access_denied");
		});
	}

	/** Mise à jour conditionnelle ; aucune ligne ⇒ demande inconnue, déjà décidée ou expirée (dans cet ordre). */
	private Decided decide(String requestUri, String decision) {
		String hash = LinkRegistry.sha256Hex(requestUri);
		return this.platform.jdbc().sql(DECIDE).param("decision", decision).param("hash", hash)
			.query((rs, n) -> new Decided((UUID) rs.getObject("id"), (UUID) rs.getObject("client_id"),
					rs.getString("external_shop_id"), rs.getString("shop_name"), rs.getString("redirect_uri"), rs.getString("state"),
					new HashSet<>(Arrays.asList((String[]) rs.getArray("scopes").getArray()))))
			.optional()
			.orElseThrow(() -> this.platform.jdbc().sql(REQUEST_STATE).param("hash", hash)
				.query((rs, n) -> new LinkConsentException(rs.getString("decision") != null
						? ErrorCode.AUTHORIZATION_REQUEST_ALREADY_DECIDED : ErrorCode.AUTHORIZATION_REQUEST_EXPIRED))
				.optional()
				.orElseGet(() -> new LinkConsentException(ErrorCode.AUTHORIZATION_REQUEST_NOT_FOUND)));
	}

	/** {@code redirect_uri} enregistrée + résultat, {@code state} tel que reçu et {@code iss} (RFC 9207), encodés. */
	private static String redirect(Decided request, String name, String value) {
		return UriComponentsBuilder.fromUriString(request.redirectUri())
			.queryParam(name, "{value}")
			.queryParam("state", "{state}")
			.queryParam("iss", "{iss}")
			.encode()
			.buildAndExpand(value, request.state(), IntegrationTokenService.ISSUER)
			.toUriString();
	}

	/** Devise ISO 4217 de l'entreprise (v11-D6), refusée explicitement si elle n'est pas prise en charge. */
	private String currency(UUID companyId) {
		String stored = this.platform.jdbc().sql(COMPANY_CURRENCY).param("id", companyId).query(String.class).optional()
			.orElseThrow(() -> new LinkConsentException(ErrorCode.COMPANY_UNAVAILABLE));
		try {
			return CurrencyCodes.iso4217(stored);
		}
		catch (UnsupportedCurrencyException ex) {
			throw new LinkConsentException(ErrorCode.LINK_CURRENCY_UNSUPPORTED);
		}
	}

	// --- Liaisons de l'entreprise ---------------------------------------------------------------------------

	List<ManagedLink> list(UUID companyId) {
		List<Link> all = this.links.listForCompany(companyId);
		Map<UUID, List<WarehouseChoice>> byLink = this.warehouses.ofLinks(all.stream().map(Link::id).toList());
		return all.stream().map(link -> new ManagedLink(link, byLink.getOrDefault(link.id(), List.of()))).toList();
	}

	ManagedLink suspend(UUID linkId, UUID companyId, Authorizer authorizer) {
		return manage(linkId, companyId, () -> this.links.suspend(linkId, SuspensionReason.MANUAL),
				link -> LinkAudit.Entry.of(LinkAudit.SUSPENDED, link, LinkAudit.Actor.user(authorizer))
					.detail("suspensionReason", SuspensionReason.MANUAL.name()));
	}

	/**
	 * Liaison suspendue seulement ; la personne qui réactive reconfirme les dépôts (la liste remplace l'ancienne) et
	 * devient responsable de la liaison (v14-D10, G3).
	 */
	ManagedLink reactivate(UUID linkId, UUID companyId, Set<UUID> warehouseIds, Authorizer authorizer) {
		Link link = own(linkId, companyId);
		if (link.status() != LinkStatus.SUSPENDED) {
			// Contrôle avant toute écriture : les dépôts d'une liaison active ne sont jamais modifiés ici.
			throw new LinkConsentException(ErrorCode.LINK_TRANSITION_INVALID);
		}
		this.warehouses.requireAccessible(warehouseIds);
		// (1) Base de l'entreprise, liaison encore suspendue : liste remplacée et disponibilité republiée si elle change,
		// dans une transaction ; (2) base plateforme.
		this.warehouses.reconfirm(linkId, warehouseIds, authorizer.userId());
		return manage(linkId, companyId, () -> this.links.reactivate(linkId, authorizer),
				reactivated -> LinkAudit.Entry.of(LinkAudit.REACTIVATED, reactivated, LinkAudit.Actor.user(authorizer))
					.detail("authorizedBy", authorizer.userId().toString()).detail("warehouses", warehouseIds));
	}

	ManagedLink revoke(UUID linkId, UUID companyId, Authorizer authorizer) {
		return manage(linkId, companyId, () -> this.links.revoke(linkId, RevocationReason.GESTSTOCK_ADMIN),
				link -> LinkAudit.Entry.of(LinkAudit.REVOKED, link, LinkAudit.Actor.user(authorizer))
					.detail("revocationReason", RevocationReason.GESTSTOCK_ADMIN.name()));
	}

	/** Liaison de l'entreprise courante seulement ; une autre entreprise reçoit la réponse d'une liaison inconnue. */
	private Link own(UUID linkId, UUID companyId) {
		return this.links.find(linkId).filter(link -> link.companyId().equals(companyId))
			.orElseThrow(() -> new LinkConsentException(ErrorCode.LINK_NOT_FOUND));
	}

	/** Changement et trace de référence dans une seule transaction plateforme (G12). */
	private ManagedLink manage(UUID linkId, UUID companyId, java.util.function.Supplier<Link> action,
			java.util.function.Function<Link, LinkAudit.Entry> trace) {
		own(linkId, companyId);
		Link link;
		try {
			link = this.audit.unit(() -> {
				Link changed = action.get();
				this.audit.record(trace.apply(changed));
				return changed;
			});
		}
		catch (LinkException ex) {
			throw LinkConsentException.of(ex);
		}
		return new ManagedLink(link, this.warehouses.ofLinks(List.of(link.id())).getOrDefault(link.id(), List.of()));
	}

	private static List<String> sorted(Array array) throws SQLException {
		return List.copyOf(new TreeSet<>(Arrays.asList((String[]) array.getArray())));
	}

	private record Pending(RequestView view, boolean decided, boolean live) {
	}

	/** Liaison et ses dépôts vendables (G4a). */
	record ManagedLink(Link link, List<WarehouseChoice> warehouses) {
	}

	private record Decided(UUID id, UUID clientId, String shopId, String shopName, String redirectUri, String state,
			Set<String> scopes) {
	}

	/**
	 * Demande affichée à l'écran de consentement ; jamais le {@code request_uri}.
	 *
	 * @param clientId   identifiant public de l'application (JeCréeMaBoutique)
	 * @param clientName nom de l'application
	 * @param shopId     identifiant de la boutique déposé par le serveur de l'application
	 * @param shopName   nom de la boutique
	 * @param scopes     portées demandées
	 * @param expiresAt  fin de validité de la demande
	 * @param currency   devise ISO 4217 de l'entreprise courante (v11-D6)
	 * @param warehouses dépôts actifs accessibles à la personne connectée, avec leur type : choix des dépôts vendables
	 *                   (G4a ; l'écran coche les magasins, STORE, par défaut)
	 */
	@Schema(name = "IntegrationAuthorizationRequest")
	record RequestView(String clientId, String clientName, String shopId, String shopName, List<String> scopes,
			Instant expiresAt, String currency, List<WarehouseChoice> warehouses) {

		RequestView with(String code, List<WarehouseChoice> choices) {
			return new RequestView(this.clientId, this.clientName, this.shopId, this.shopName, this.scopes, this.expiresAt, code,
					choices);
		}

	}

}
