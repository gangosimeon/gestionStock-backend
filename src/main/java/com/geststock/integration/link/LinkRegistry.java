package com.geststock.integration.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.geststock.integration.CurrencyCodes;
import com.geststock.integration.UnsupportedCurrencyException;
import com.geststock.platform.PlatformDb;

/**
 * Cycle de vie des liaisons dans la base plateforme (lot 2, étape G3 ; v14-D2, D3, D5, D10, D13, D14, ajustements v14).
 *
 * <p><b>Transactions</b> : celles de {@link PlatformDb#inTransaction} (gestionnaire {@code DataSourceTransactionManager}
 * propre à la base plateforme), jamais le gestionnaire JPA des bases d'entreprise ni {@code @Transactional}. Les
 * opérations rejoignent une transaction plateforme déjà ouverte (propagation {@code REQUIRED}) : l'approbation (étape
 * G10) pourra enchaîner décision, liaison en attente et code dans une seule transaction.
 *
 * <p><b>Horloge</b> : celle de PostgreSQL ({@code now()}), comme les valeurs par défaut et les {@code CHECK} du schéma
 * (V002) : une seule source de vérité pour toutes les instances de l'application, et des comparaisons
 * ({@code expires_at <= now()}) faites dans la même transaction que les écritures.
 *
 * <p><b>Transitions</b> : chacune est une mise à jour conditionnée par le statut courant ({@code WHERE status = …});
 * aucune ligne modifiée ⇒ {@link LinkException.Reason#INVALID_TRANSITION} (ou {@code LINK_NOT_FOUND}), jamais une
 * transition silencieuse.
 *
 * <p><b>Secret de liaison</b> : 256 bits ({@link SecureRandom}), base64url sans remplissage, renvoyé une seule fois par
 * {@link #activate} ou {@link #rotate} ; seule son empreinte SHA-256 (hexadécimal minuscule) est conservée et comparée
 * en temps constant. Les empreintes liées aux requêtes sont masquées dans les journaux ({@link MaskedText}).
 *
 * <p><b>Rotation</b> (G14, v14-D6) : le nouveau secret attend ({@code pending_secret_hash}) ; l'ancien et le nouveau sont
 * acceptés jusqu'au premier usage du nouveau ({@link #promote}), 24 h au plus : au-delà, le secret en attente est promu
 * à la première vérification et l'ancien refusé.
 */
@Component
public class LinkRegistry {

	private static final Logger log = LoggerFactory.getLogger(LinkRegistry.class);

	/** Durée de vie d'un code d'autorisation : au-delà, une liaison en attente sans code est aussi expirée. */
	static final String CODE_LIFETIME = "5 minutes";

	private static final int SECRET_BYTES = 32;

	private static final String ACTIVE_SHOP_INDEX = "uk_integration_link_active_shop";

	private static final String LINK_COLUMNS = """
			id, client_id, company_id, external_shop_id, shop_name, status, suspension_reason, revocation_reason, currency,
			scopes, authorized_by, authorized_by_username, created_at, activated_at, suspended_at, revoked_at""";

	private static final String REQUEST = """
			select r.client_id, r.external_shop_id, r.shop_name, r.scopes, r.decision, c.status as client_status,
			       c.allowed_scopes
			from platform.integration_authorization_request r
			join platform.integration_client c on c.id = r.client_id
			where r.id = :id
			for update of r""";

	private static final String COMPANY = "select status, currency from platform.company where id = :id for share";

	/** Liaisons en attente de la boutique dont le code a expiré (ou, sans code, plus anciennes que sa durée de vie). */
	private static final String EXPIRE_STALE_PENDING = """
			update platform.integration_link l
			set status = 'REVOKED', revocation_reason = 'EXPIRED', revoked_at = now(), updated_at = now()
			where l.client_id = :client and l.external_shop_id = :shop and l.status = 'PENDING'
			  and (exists (select 1 from platform.integration_authorization_code c
			               where c.link_id = l.id and c.expires_at <= now())
			       or (not exists (select 1 from platform.integration_authorization_code c where c.link_id = l.id)
			           and l.created_at <= now() - interval '""" + CODE_LIFETIME + "')) returning " + LINK_COLUMNS;

	private static final String INSERT_PENDING = """
			insert into platform.integration_link (id, client_id, company_id, external_shop_id, shop_name, status, currency,
			  scopes, authorized_by, authorized_by_username)
			values (:id, :client, :company, :shop, :name, 'PENDING', :currency, string_to_array(:scopes, ' ')::varchar(30)[],
			  :authorizedBy, :username)
			returning\s""" + LINK_COLUMNS;

	private static final String ACTIVATE = """
			update platform.integration_link
			set status = 'ACTIVE', secret_hash = :hash, activated_at = now(), updated_at = now()
			where id = :id and status = 'PENDING'
			returning\s""" + LINK_COLUMNS;

	private static final String SUSPEND = """
			update platform.integration_link
			set status = 'SUSPENDED', suspension_reason = :reason, suspended_at = now(), updated_at = now()
			where id = :id and status = 'ACTIVE'
			returning\s""" + LINK_COLUMNS;

	private static final String REACTIVATE = """
			update platform.integration_link
			set status = 'ACTIVE', suspension_reason = null, updated_at = now(),
			    authorized_by = :authorizedBy, authorized_by_username = :username
			where id = :id and status = 'SUSPENDED'
			returning\s""" + LINK_COLUMNS;

	private static final String REVOKE = """
			update platform.integration_link
			set status = 'REVOKED', revocation_reason = :reason, revoked_at = now(), updated_at = now(),
			    suspension_reason = null, secret_hash = null, pending_secret_hash = null, pending_secret_created_at = null
			where id = :id and status in (%s)
			returning\s""" + LINK_COLUMNS;

	private static final String REVOKE_ANY = REVOKE.formatted("'PENDING', 'ACTIVE', 'SUSPENDED'");

	/** {@code EXPIRED} : réservé à une liaison jamais activée (cohérent avec {@code ck_integration_link_activated}). */
	private static final String REVOKE_PENDING = REVOKE.formatted("'PENDING'");

	/** Validité de l'ancien secret après une rotation, au plus (v14-D6). */
	static final String PREVIOUS_SECRET_GRACE = "24 hours";

	private static final String SECRETS = """
			select secret_hash, pending_secret_hash from platform.integration_link
			where id = :id and status in ('ACTIVE', 'SUSPENDED')""";

	/** Rotation : liaison ACTIVE seulement ; un secret déjà en attente est remplacé (l'ancien courant reste valide). */
	private static final String ROTATE = """
			update platform.integration_link
			set pending_secret_hash = :hash, pending_secret_created_at = now(), updated_at = now()
			where id = :id and status = 'ACTIVE'
			returning\s""" + LINK_COLUMNS + """
			, pending_secret_created_at, pending_secret_created_at + interval '""" + PREVIOUS_SECRET_GRACE
			+ "' as previous_secret_valid_until";

	private static final String PROMOTION = """
			update platform.integration_link
			set secret_hash = pending_secret_hash, pending_secret_hash = null, pending_secret_created_at = null,
			    updated_at = now()
			where id = :id and status in ('ACTIVE', 'SUSPENDED') and\s""";

	/** Premier usage du secret en attente : une seule mise à jour conditionnelle (une seule promotion). */
	private static final String PROMOTE_ON_FIRST_USE = PROMOTION + "pending_secret_hash = :hash returning " + LINK_COLUMNS;

	/** Secret en attente émis il y a plus de 24 h : promu, l'ancien cesse d'être accepté. */
	private static final String PROMOTE_EXPIRED = PROMOTION + "pending_secret_created_at <= now() - interval '"
			+ PREVIOUS_SECRET_GRACE + "' returning " + LINK_COLUMNS;

	private static final String FIRST_USE = "FIRST_USE";

	private static final String PREVIOUS_SECRET_EXPIRED = "PREVIOUS_SECRET_EXPIRED";

	private static final byte[] NO_HASH = new byte[64];

	private final PlatformDb platform;

	private final SecureRandom random = new SecureRandom();

	private final LinkAudit audit;

	LinkRegistry(PlatformDb platform, LinkAudit audit) {
		this.platform = platform;
		this.audit = audit;
	}

	/** Révocation EXPIRED des liaisons en attente expirées de la boutique, chacune tracée (auteur : le système, G12). */
	private int expireStalePending(UUID clientId, String externalShopId) {
		List<Link> expired = this.platform.jdbc().sql(EXPIRE_STALE_PENDING).param("client", clientId)
			.param("shop", externalShopId).query(LinkRegistry::link).list();
		for (Link link : expired) {
			this.audit.record(LinkAudit.Entry.of(LinkAudit.REVOKED, link, LinkAudit.Actor.system("expiration"))
				.detail("revocationReason", RevocationReason.EXPIRED.name()));
		}
		return expired.size();
	}

	/**
	 * Crée la liaison en attente d'une demande approuvée. Dans la même transaction, révoque d'abord ({@code EXPIRED})
	 * les liaisons en attente expirées de la même boutique et du même client.
	 *
	 * @param requestId     demande d'autorisation, décision {@code APPROVED}
	 * @param companyId     entreprise de la personne qui approuve (jamais l'entreprise courante du contexte)
	 * @param authorizer    personne qui approuve
	 * @param grantedScopes portées accordées, incluses dans celles de la demande
	 * @throws LinkException demande, client, entreprise, devise ou portées refusées ; boutique déjà liée
	 */
	public Link createPending(UUID requestId, UUID companyId, Authorizer authorizer, Set<String> grantedScopes) {
		return createPending(UUID.randomUUID(), requestId, companyId, authorizer, grantedScopes);
	}

	/**
	 * Comme {@link #createPending(UUID, UUID, Authorizer, Set)}, avec l'identifiant de la liaison choisi par l'appelant :
	 * l'approbation (G4a) écrit d'abord les dépôts de la liaison dans la base de l'entreprise, sous cet identifiant.
	 */
	public Link createPending(UUID linkId, UUID requestId, UUID companyId, Authorizer authorizer, Set<String> grantedScopes) {
		if (linkId == null || requestId == null || companyId == null || authorizer == null || authorizer.userId() == null
				|| authorizer.username() == null || authorizer.username().isBlank() || authorizer.username().length() > 50
				|| grantedScopes == null || grantedScopes.isEmpty()) {
			throw new LinkException(LinkException.Reason.INVALID_ARGUMENT);
		}
		try {
			return this.platform.inTransaction(status -> {
				PendingRequest request = this.platform.jdbc().sql(REQUEST).param("id", requestId)
					.query((rs, n) -> new PendingRequest((UUID) rs.getObject("client_id"), rs.getString("external_shop_id"),
							rs.getString("shop_name"), strings(rs.getArray("scopes")), rs.getString("decision"),
							rs.getString("client_status"), strings(rs.getArray("allowed_scopes"))))
					.optional()
					.orElseThrow(() -> new LinkException(LinkException.Reason.REQUEST_NOT_FOUND));
				if (!"APPROVED".equals(request.decision())) {
					throw new LinkException(LinkException.Reason.REQUEST_NOT_APPROVED);
				}
				if (!"ACTIVE".equals(request.clientStatus())) {
					throw new LinkException(LinkException.Reason.CLIENT_DISABLED);
				}
				if (!request.allowedScopes().containsAll(request.scopes())) {
					throw new LinkException(LinkException.Reason.SCOPE_NOT_ALLOWED);
				}
				if (!request.scopes().containsAll(grantedScopes)) {
					throw new LinkException(LinkException.Reason.SCOPE_NOT_REQUESTED);
				}
				String currency = companyCurrency(companyId);
				int expired = expireStalePending(request.clientId(), request.externalShopId());
				if (expired > 0) {
					log.info("Liaison(s) en attente expirée(s) révoquée(s) (EXPIRED) avant une nouvelle demande : {}.", expired);
				}
				Link link = this.platform.jdbc().sql(INSERT_PENDING).param("id", linkId).param("client", request.clientId())
					.param("company", companyId)
					.param("shop", request.externalShopId()).param("name", request.shopName()).param("currency", currency)
					.param("scopes", String.join(" ", new TreeSet<>(grantedScopes))).param("authorizedBy", authorizer.userId())
					.param("username", authorizer.username())
					.query(LinkRegistry::link).single();
				log.info("Liaison {} créée en attente (entreprise {}).", link.id(), companyId);
				return link;
			});
		}
		catch (DuplicateKeyException ex) {
			if (String.valueOf(ex.getMessage()).contains(ACTIVE_SHOP_INDEX)) {
				throw new LinkException(LinkException.Reason.SHOP_ALREADY_LINKED);
			}
			throw ex;
		}
	}

	/**
	 * Demande déposée ({@code POST /oauth/par}, v14-D2) : révoque d'abord ({@code EXPIRED}) les liaisons en attente
	 * expirées de la boutique pour ce client, puis indique s'il lui reste une liaison non révoquée. L'index unique
	 * partiel reste la garantie finale, à la création de la liaison.
	 */
	public boolean shopAlreadyLinked(UUID clientId, String externalShopId) {
		return this.platform.inTransaction(status -> {
			expireStalePending(clientId, externalShopId);
			return this.platform.jdbc().sql("""
					select exists (select 1 from platform.integration_link
					               where client_id = :client and external_shop_id = :shop
					                 and status in ('PENDING', 'ACTIVE', 'SUSPENDED'))""")
				.param("client", clientId).param("shop", externalShopId).query(Boolean.class).single();
		});
	}

	/**
	 * PENDING → ACTIVE : génère le secret de liaison, en conserve l'empreinte et le renvoie, cette seule fois.
	 */
	public ActivatedLink activate(UUID linkId) {
		String secret = newSecret();
		Link link = transition(linkId, () -> this.platform.jdbc().sql(ACTIVATE).param("id", linkId)
			.param("hash", maskedHash(secret)).query(LinkRegistry::link).optional());
		log.info("Liaison {} activée.", link.id());
		return new ActivatedLink(link, secret);
	}

	/** ACTIVE → SUSPENDED, avec un motif (v14-D10). */
	public Link suspend(UUID linkId, SuspensionReason reason) {
		if (reason == null) {
			throw new LinkException(LinkException.Reason.INVALID_ARGUMENT);
		}
		Link link = transition(linkId, () -> this.platform.jdbc().sql(SUSPEND).param("id", linkId).param("reason", reason.name())
			.query(LinkRegistry::link).optional());
		log.info("Liaison {} suspendue ({}).", link.id(), reason);
		return link;
	}

	/**
	 * SUSPENDED → ACTIVE ; le secret est conservé. La personne qui réactive reconfirme les dépôts (v14-D10, étapes G4
	 * et G10) et devient responsable de la liaison : elle remplace la personne d'origine dans la même mise à jour, pour
	 * qu'une liaison suspendue AUTHORIZER_LOST_ACCESS ne référence plus quelqu'un qui a perdu ses droits.
	 */
	public Link reactivate(UUID linkId, Authorizer authorizer) {
		requireAuthorizer(authorizer);
		Link link = transition(linkId, () -> this.platform.jdbc().sql(REACTIVATE).param("id", linkId)
			.param("authorizedBy", authorizer.userId()).param("username", authorizer.username())
			.query(LinkRegistry::link).optional());
		log.info("Liaison {} réactivée.", link.id());
		return link;
	}

	/**
	 * PENDING, ACTIVE ou SUSPENDED → REVOKED : efface les empreintes des secrets. {@code EXPIRED} n'est accepté que
	 * pour une liaison en attente (jamais activée).
	 */
	public Link revoke(UUID linkId, RevocationReason reason) {
		if (reason == null) {
			throw new LinkException(LinkException.Reason.INVALID_ARGUMENT);
		}
		String sql = reason == RevocationReason.EXPIRED ? REVOKE_PENDING : REVOKE_ANY;
		Link link = transition(linkId, () -> this.platform.jdbc().sql(sql).param("id", linkId).param("reason", reason.name())
			.query(LinkRegistry::link).optional());
		log.info("Liaison {} révoquée ({}).", link.id(), reason);
		return link;
	}

	/**
	 * Le secret est-il accepté (secret courant ou secret en attente de rotation) ? Faux pour une liaison inconnue, en
	 * attente ou révoquée. Voir {@link #checkSecret}.
	 */
	public boolean verifySecret(UUID linkId, String secret) {
		return checkSecret(linkId, secret) != SecretMatch.NONE;
	}

	/** Secret présenté : celui qu'il a reconnu (G14). */
	public enum SecretMatch {
		/** Aucun : refusé. */
		NONE,
		/** Le secret courant. */
		CURRENT,
		/** Le secret en attente de rotation : à promouvoir au premier usage réussi. */
		PENDING
	}

	/**
	 * Vérifie le secret présenté contre le secret courant et le secret en attente (G14). Au-delà de 24 h après
	 * l'émission du secret en attente, celui-ci est d'abord promu (l'ancien est alors refusé).
	 */
	public SecretMatch checkSecret(UUID linkId, String secret) {
		if (linkId == null || secret == null) {
			return SecretMatch.NONE;
		}
		// Indépendant du secret présenté : la promotion après 24 h ne révèle rien.
		this.platform.inTransaction(status -> promoteExpired(linkId));
		Optional<String[]> stored = this.platform.jdbc().sql(SECRETS).param("id", linkId)
			.query((rs, n) -> new String[] { rs.getString("secret_hash"), rs.getString("pending_secret_hash") })
			.optional();
		String[] hashes = stored.orElse(new String[2]);
		SecretMatch match = match(sha256Hex(secret).getBytes(StandardCharsets.US_ASCII), ascii(hashes[0]), ascii(hashes[1]),
				MessageDigest::isEqual);
		return stored.isPresent() ? match : SecretMatch.NONE;
	}

	/**
	 * Rotation (G14) demandée par la boutique : nouveau secret en attente, renvoyé une seule fois ; liaison ACTIVE
	 * seulement.
	 * @throws LinkException liaison inconnue ou non active
	 */
	public RotatedSecret rotate(UUID linkId) {
		String secret = newSecret();
		RotatedSecret rotated = this.platform.inTransaction(status -> {
			// Un secret en attente de plus de 24 h est d'abord promu : le remplacer rendrait vie à l'ancien secret.
			promoteExpired(linkId);
			boolean replaced = this.platform.jdbc().sql("""
					select pending_secret_hash is not null from platform.integration_link
					where id = :id and status = 'ACTIVE' for update""").param("id", linkId).query(Boolean.class).optional()
				.orElse(false);
			RotatedSecret result = transition(linkId, () -> this.platform.jdbc().sql(ROTATE).param("id", linkId)
				.param("hash", maskedHash(secret))
				.query((rs, n) -> new RotatedSecret(link(rs, n), secret, instant(rs, "pending_secret_created_at"),
						instant(rs, "previous_secret_valid_until")))
				.optional());
			this.audit.record(LinkAudit.Entry.of(LinkAudit.SECRET_ROTATED, result.link(), LinkAudit.Actor.shop(result.link()))
				.detail("pendingSecretReplaced", replaced)
				.detail("previousSecretValidUntil", result.previousSecretValidUntil().toString()));
			return result;
		});
		log.info("Liaison {} : rotation du secret, nouveau secret en attente.", linkId);
		return rotated;
	}

	/**
	 * Promotion au premier usage réussi du secret en attente (G14) : une seule mise à jour conditionnelle, sur
	 * l'empreinte présentée ; faux si une autre requête l'a déjà promu, ou si une rotation l'a remplacé entre-temps.
	 */
	boolean promote(UUID linkId, String secret, LinkAudit.Actor actor) {
		return this.platform.inTransaction(status -> {
			Optional<Link> promoted = this.platform.jdbc().sql(PROMOTE_ON_FIRST_USE).param("id", linkId)
				.param("hash", maskedHash(secret)).query(LinkRegistry::link).optional();
			promoted.ifPresent(link -> {
				this.audit.record(LinkAudit.Entry.of(LinkAudit.SECRET_PROMOTED, link, actor).detail("cause", FIRST_USE));
				log.info("Liaison {} : nouveau secret promu à son premier usage.", link.id());
			});
			return promoted.isPresent();
		});
	}

	/** Secret en attente de plus de 24 h promu, dans la transaction plateforme courante ; tracé (auteur : le système). */
	private Void promoteExpired(UUID linkId) {
		this.platform.jdbc().sql(PROMOTE_EXPIRED).param("id", linkId).query(LinkRegistry::link).optional().ifPresent(link -> {
			this.audit.record(LinkAudit.Entry.of(LinkAudit.SECRET_PROMOTED, link, LinkAudit.Actor.system("délai de 24 h"))
				.detail("cause", PREVIOUS_SECRET_EXPIRED));
			log.info("Liaison {} : secret en attente promu, l'ancien secret a dépassé 24 h.", link.id());
		});
		return null;
	}

	/**
	 * Comparaison du secret présenté (empreinte) avec les deux empreintes, courante et en attente : les deux comparaisons
	 * ont toujours lieu, sans court-circuit, sur 64 octets (empreinte absente : tableau neutre de même longueur), en
	 * temps constant ({@link MessageDigest#isEqual}) : le travail fait ne révèle pas lequel a servi.
	 */
	static SecretMatch match(byte[] presented, byte[] current, byte[] pending, BiPredicate<byte[], byte[]> equal) {
		boolean isCurrent = equal.test(presented, current != null ? current : NO_HASH);
		boolean isPending = equal.test(presented, pending != null ? pending : NO_HASH);
		return isCurrent ? SecretMatch.CURRENT : isPending ? SecretMatch.PENDING : SecretMatch.NONE;
	}

	private static byte[] ascii(String hash) {
		return hash != null ? hash.getBytes(StandardCharsets.US_ASCII) : null;
	}

	/** Nouveau secret : 256 bits aléatoires, base64url sans remplissage. */
	private String newSecret() {
		byte[] raw = new byte[SECRET_BYTES];
		this.random.nextBytes(raw);
		String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
		Arrays.fill(raw, (byte) 0);
		return secret;
	}

	/** Empreinte d'un secret, liée à une requête sans apparaître dans les journaux (TRACE). */
	private static MaskedText maskedHash(String secret) {
		return new MaskedText(sha256Hex(secret));
	}

	public Optional<Link> find(UUID linkId) {
		if (linkId == null) {
			return Optional.empty();
		}
		return this.platform.jdbc().sql("select " + LINK_COLUMNS + " from platform.integration_link where id = :id")
			.param("id", linkId).query(LinkRegistry::link).optional();
	}

	/** Liaisons de l'entreprise, la plus récente d'abord (G10). */
	public List<Link> listForCompany(UUID companyId) {
		return this.platform.jdbc()
			.sql("select " + LINK_COLUMNS + " from platform.integration_link where company_id = :company order by created_at desc, id")
			.param("company", companyId).query(LinkRegistry::link).list();
	}

	private static void requireAuthorizer(Authorizer authorizer) {
		if (authorizer == null || authorizer.userId() == null || authorizer.username() == null
				|| authorizer.username().isBlank() || authorizer.username().length() > 50) {
			throw new LinkException(LinkException.Reason.INVALID_ARGUMENT);
		}
	}

	/** Mise à jour conditionnelle ; aucune ligne ⇒ liaison inconnue ou transition refusée. */
	private <T> T transition(UUID linkId, Supplier<Optional<T>> update) {
		if (linkId == null) {
			throw new LinkException(LinkException.Reason.INVALID_ARGUMENT);
		}
		return this.platform.inTransaction(status -> update.get().orElseThrow(() -> {
			boolean exists = this.platform.jdbc().sql("select count(*) from platform.integration_link where id = :id")
				.param("id", linkId).query(Integer.class).single() > 0;
			return new LinkException(exists ? LinkException.Reason.INVALID_TRANSITION : LinkException.Reason.LINK_NOT_FOUND);
		}));
	}

	/** Devise ISO 4217 de l'entreprise donnée (v11-D6) ; entreprise inconnue ou non active refusée. */
	private String companyCurrency(UUID companyId) {
		String[] company = this.platform.jdbc().sql(COMPANY).param("id", companyId)
			.query((rs, n) -> new String[] { rs.getString("status"), rs.getString("currency") })
			.optional()
			.orElseThrow(() -> new LinkException(LinkException.Reason.COMPANY_NOT_FOUND));
		if (!"ACTIVE".equals(company[0])) {
			throw new LinkException(LinkException.Reason.COMPANY_NOT_ACTIVE);
		}
		try {
			return CurrencyCodes.iso4217(company[1]);
		}
		catch (UnsupportedCurrencyException ex) {
			throw new LinkException(LinkException.Reason.CURRENCY_UNSUPPORTED);
		}
	}

	private static Link link(ResultSet rs, int row) throws SQLException {
		return new Link((UUID) rs.getObject("id"), (UUID) rs.getObject("client_id"), (UUID) rs.getObject("company_id"),
				rs.getString("external_shop_id"), rs.getString("shop_name"), LinkStatus.valueOf(rs.getString("status")),
				enumOrNull(SuspensionReason.class, rs.getString("suspension_reason")),
				enumOrNull(RevocationReason.class, rs.getString("revocation_reason")), rs.getString("currency"),
				strings(rs.getArray("scopes")), (UUID) rs.getObject("authorized_by"), rs.getString("authorized_by_username"),
				instant(rs, "created_at"), instant(rs, "activated_at"), instant(rs, "suspended_at"), instant(rs, "revoked_at"));
	}

	private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
		return value != null ? Enum.valueOf(type, value) : null;
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value != null ? value.toInstant() : null;
	}

	private static Set<String> strings(Array array) throws SQLException {
		return array != null ? new HashSet<>(Arrays.asList((String[]) array.getArray())) : Set.of();
	}

	static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record PendingRequest(UUID clientId, String externalShopId, String shopName, Set<String> scopes, String decision,
			String clientStatus, Set<String> allowedScopes) {
	}

}
