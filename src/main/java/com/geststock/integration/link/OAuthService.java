package com.geststock.integration.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import com.geststock.platform.PlatformDb;

/**
 * Routes du client JeCréeMaBoutique (lot 2, étape G8 ; v14-D1, D3, D12, D14) : dépôt de la demande d'autorisation
 * (PAR, RFC 9126), échange du code d'autorisation (PKCE S256) et {@code client_credentials}. Le client est déjà
 * authentifié (HTTP Basic, chaîne d'intégration).
 *
 * <p>Ni secret, ni code, ni vérificateur, ni {@code request_uri} n'est journalisé ni stocké en clair : seulement leurs
 * empreintes SHA-256. L'éligibilité de la boutique (approuvée, non bannie, propriétaire et non {@code admin},
 * v14-D13) est vérifiée par JeCréeMaBoutique avant l'appel : GestStock ne peut pas la contrôler.
 */
@Service
class OAuthService {

	private static final Logger log = LoggerFactory.getLogger(OAuthService.class);

	static final String REQUEST_URI_PREFIX = "urn:ietf:params:oauth:request_uri:";

	static final int REQUEST_URI_LIFETIME_SECONDS = 600;

	private static final Pattern CODE_CHALLENGE = Pattern.compile("^[A-Za-z0-9_-]{43,128}$");

	private static final Pattern CODE_VERIFIER = Pattern.compile("^[A-Za-z0-9._~-]{43,128}$");

	private static final String INSERT_REQUEST = """
			insert into platform.integration_authorization_request (request_uri_hash, client_id, external_shop_id, shop_name,
			  redirect_uri, code_challenge, code_challenge_method, scopes, state, expires_at)
			values (:hash, :client, :shop, :name, :redirect, :challenge, 'S256', string_to_array(:scopes, ' ')::varchar(30)[],
			  :state, now() + interval '10 minutes')""";

	/** Consommation du code : une seule mise à jour conditionnelle (non utilisé, non expiré, demande de ce client). */
	static final String CONSUME_CODE = """
			update platform.integration_authorization_code c set used_at = now()
			from platform.integration_authorization_request r
			where c.request_id = r.id and c.code_hash = :hash and r.client_id = :client
			  and c.used_at is null and c.expires_at > now()
			returning c.link_id, r.redirect_uri, r.code_challenge""";

	private static final String USED_CODE = """
			select c.link_id from platform.integration_authorization_code c
			join platform.integration_authorization_request r on r.id = c.request_id
			where c.code_hash = :hash and r.client_id = :client and c.used_at is not null""";

	private final PlatformDb platform;

	private final LinkRegistry links;

	private final IntegrationTokenService tokens;

	private final SecureRandom random = new SecureRandom();

	OAuthService(PlatformDb platform, LinkRegistry links, IntegrationTokenService tokens) {
		this.platform = platform;
		this.links = links;
		this.tokens = tokens;
	}

	// --- PAR ------------------------------------------------------------------------------------------------

	/** Dépose la demande ; renvoie le {@code request_uri} (à usage unique, 10 min), dont seule l'empreinte est stockée. */
	Map<String, Object> push(IntegrationClients.AuthenticatedClient client, Map<String, String> params) {
		if (!"code".equals(params.get("response_type"))) {
			throw OAuthException.invalidRequest("response_type doit valoir code.");
		}
		String redirectUri = required(params, "redirect_uri");
		boolean registered = this.platform.jdbc().sql("""
				select exists (select 1 from platform.integration_client_redirect_uri where client_id = :client and redirect_uri = :uri)""")
			.param("client", client.id()).param("uri", redirectUri).query(Boolean.class).single();
		if (!registered) {
			throw OAuthException.invalidRequest("redirect_uri non enregistrée pour ce client.");
		}
		String challenge = required(params, "code_challenge");
		if (!CODE_CHALLENGE.matcher(challenge).matches()) {
			throw OAuthException.invalidRequest("code_challenge invalide.");
		}
		if (!"S256".equals(params.get("code_challenge_method"))) {
			throw OAuthException.invalidRequest("code_challenge_method doit valoir S256.");
		}
		Set<String> scopes = scopes(params.get("scope"));
		if (scopes.isEmpty() || !client.allowedScopes().containsAll(scopes)) {
			throw OAuthException.invalidScope("Portées absentes ou non autorisées pour ce client.");
		}
		String state = required(params, "state");
		if (state.length() < 32 || state.length() > 512) {
			throw OAuthException.invalidRequest("state doit compter de 32 à 512 caractères.");
		}
		String shopId = required(params, "shop_id");
		String shopName = required(params, "shop_name");
		if (shopId.isBlank() || shopId.length() > 64 || shopName.isBlank() || shopName.length() > 200) {
			throw OAuthException.invalidRequest("shop_id ou shop_name invalide.");
		}
		String requestUri = REQUEST_URI_PREFIX + randomToken();
		this.platform.inTransaction(status -> {
			if (this.links.shopAlreadyLinked(client.id(), shopId)) {
				throw OAuthException.invalidRequest("Boutique déjà liée.");
			}
			this.platform.jdbc().sql(INSERT_REQUEST).param("hash", LinkRegistry.sha256Hex(requestUri)).param("client", client.id())
				.param("shop", shopId).param("name", shopName).param("redirect", redirectUri).param("challenge", challenge)
				.param("scopes", String.join(" ", new TreeSet<>(scopes))).param("state", state).update();
			return null;
		});
		log.info("Demande d'autorisation déposée par le client {} pour la boutique {}.", client.clientId(), shopId);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("request_uri", requestUri);
		body.put("expires_in", REQUEST_URI_LIFETIME_SECONDS);
		return body;
	}

	// --- authorization_code ------------------------------------------------------------------------------

	/**
	 * Échange un code : consommation conditionnelle unique, {@code redirect_uri} identique, PKCE S256, puis activation
	 * de la liaison. Un code déjà utilisé est un rejeu (v14-D14) : la liaison qu'il a créée est révoquée
	 * ({@code CODE_REPLAYED}) dans la transaction qui constate le rejeu, puis la réponse est {@code invalid_grant}.
	 */
	Map<String, Object> exchangeCode(IntegrationClients.AuthenticatedClient client, Map<String, String> params) {
		String code = required(params, "code");
		String redirectUri = required(params, "redirect_uri");
		String verifier = required(params, "code_verifier");
		if (!CODE_VERIFIER.matcher(verifier).matches()) {
			throw OAuthException.invalidRequest("code_verifier invalide.");
		}
		String codeHash = LinkRegistry.sha256Hex(code);
		Exchange exchange = this.platform.inTransaction(status -> {
			Optional<ConsumedCode> consumed = this.platform.jdbc().sql(CONSUME_CODE).param("hash", codeHash).param("client", client.id())
				.query((rs, n) -> new ConsumedCode((UUID) rs.getObject("link_id"), rs.getString("redirect_uri"),
						rs.getString("code_challenge")))
				.optional();
			if (consumed.isEmpty()) {
				Optional<UUID> replayed = this.platform.jdbc().sql(USED_CODE).param("hash", codeHash).param("client", client.id())
					.query(UUID.class).optional();
				replayed.ifPresent(this::revokeReplayed);
				return new Exchange(null, replayed.isPresent());
			}
			ConsumedCode found = consumed.get();
			if (!found.redirectUri().equals(redirectUri) || !pkceMatches(verifier, found.codeChallenge())) {
				// Annule la consommation : le code reste utilisable avec les bons paramètres.
				throw OAuthException.invalidGrant();
			}
			try {
				return new Exchange(this.links.activate(found.linkId()), false);
			}
			catch (LinkException ex) {
				throw OAuthException.invalidGrant();
			}
		});
		if (exchange.activated() == null) {
			if (exchange.replay()) {
				log.info("Code d'autorisation rejoué par le client {} : liaison révoquée (CODE_REPLAYED).", client.clientId());
			}
			throw OAuthException.invalidGrant();
		}
		Link link = exchange.activated().link();
		log.info("Liaison {} activée par l'échange du code (client {}).", link.id(), client.clientId());
		Map<String, Object> body = tokenBody(link.id(), client.clientId(), link.scopes());
		body.put("link", linkView(link));
		body.put("link_secret", exchange.activated().secret());
		return body;
	}

	private void revokeReplayed(UUID linkId) {
		try {
			this.links.revoke(linkId, RevocationReason.CODE_REPLAYED);
		}
		catch (LinkException ex) {
			// Déjà révoquée : rien à faire, le refus suit.
		}
	}

	// --- client_credentials --------------------------------------------------------------------------------

	/**
	 * Jeton d'une liaison du client : liaison d'un autre client ou inconnue ⇒ {@code invalid_grant} ; révoquée ⇒
	 * {@code 400 invalid_grant} avec {@code code LINK_REVOKED} ; secret vérifié en temps constant ; liaison active ou
	 * suspendue ⇒ jeton de 15 min.
	 */
	Map<String, Object> clientCredentials(IntegrationClients.AuthenticatedClient client, Map<String, String> params) {
		UUID linkId;
		try {
			linkId = UUID.fromString(required(params, "link_id"));
		}
		catch (IllegalArgumentException ex) {
			throw OAuthException.invalidRequest("link_id invalide.");
		}
		String secret = required(params, "link_secret");
		Link link = this.links.find(linkId).filter(found -> found.clientId().equals(client.id()))
			.orElseThrow(OAuthException::invalidGrant);
		if (link.status() == LinkStatus.REVOKED) {
			throw OAuthException.linkRevoked();
		}
		if (!this.links.verifySecret(linkId, secret)) {
			log.info("Liaison {} : secret de liaison refusé (client {}).", linkId, client.clientId());
			throw OAuthException.invalidGrant();
		}
		Set<String> scopes = link.scopes();
		if (params.containsKey("scope")) {
			Set<String> requested = scopes(params.get("scope"));
			if (requested.isEmpty() || !link.scopes().containsAll(requested)) {
				throw OAuthException.invalidScope("Portées demandées hors de celles de la liaison.");
			}
			scopes = requested;
		}
		return tokenBody(linkId, client.clientId(), scopes);
	}

	// --- Outils ---------------------------------------------------------------------------------------------

	private Map<String, Object> tokenBody(UUID linkId, String clientId, Set<String> scopes) {
		LinkAccessToken token = this.tokens.issue(linkId, clientId, scopes);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("access_token", token.value());
		body.put("token_type", "Bearer");
		body.put("expires_in", token.expiresInSeconds());
		body.put("scope", String.join(" ", new TreeSet<>(scopes)));
		return body;
	}

	/** Liaison au format {@code Link} du contrat ; dépôts et réglages arrivent aux étapes G4 et suivantes. */
	private Map<String, Object> linkView(Link link) {
		String companyCode = this.platform.jdbc().sql("select code from platform.company where id = :id").param("id", link.companyId())
			.query(String.class).single();
		Map<String, Object> view = new LinkedHashMap<>();
		view.put("id", link.id());
		view.put("status", link.status());
		view.put("suspensionReason", link.suspensionReason());
		view.put("revocationReason", link.revocationReason());
		view.put("companyCode", companyCode);
		view.put("currency", link.currency());
		view.put("externalShopId", link.externalShopId());
		view.put("scopes", List.copyOf(new TreeSet<>(link.scopes())));
		view.put("warehouses", List.of());
		view.put("settings", Map.of());
		view.put("createdAt", link.createdAt());
		view.put("lastSyncAt", null);
		view.put("revokedAt", link.revokedAt());
		return view;
	}

	private static boolean pkceMatches(String verifier, String challenge) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
			return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII), challenge.getBytes(StandardCharsets.US_ASCII));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Set<String> scopes(String value) {
		Set<String> scopes = new LinkedHashSet<>();
		if (value != null) {
			for (String scope : value.trim().split(" +")) {
				if (!scope.isEmpty()) {
					scopes.add(scope);
				}
			}
		}
		return scopes;
	}

	private static String required(Map<String, String> params, String name) {
		String value = params.get(name);
		if (value == null) {
			throw OAuthException.invalidRequest("Paramètre requis : " + name + ".");
		}
		return value;
	}

	private String randomToken() {
		byte[] bytes = new byte[32];
		this.random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private record ConsumedCode(UUID linkId, String redirectUri, String codeChallenge) {
	}

	private record Exchange(ActivatedLink activated, boolean replay) {
	}

}
