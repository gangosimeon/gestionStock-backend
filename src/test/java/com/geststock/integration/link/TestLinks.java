package com.geststock.integration.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.geststock.platform.PlatformDb;

/**
 * Liaisons réelles pour les tests de la chaîne d'intégration : client, demande approuvée, liaison créée et activée par
 * {@link LinkRegistry}. Tout ce qui est créé est supprimé par {@link #cleanUp()}.
 */
final class TestLinks {

	static final String REDIRECT_URI = "https://www.jecreemaboutique.com/integrations/geststock/callback";

	private static final Authorizer ADMIN = new Authorizer(UUID.randomUUID(), "admin");

	private final PlatformDb platform;

	private final LinkRegistry registry;

	private final List<UUID> clients = new ArrayList<>();

	TestLinks(PlatformDb platform, LinkRegistry registry) {
		this.platform = platform;
		this.registry = registry;
	}

	/** Client actif autorisé à toutes les portées du contrat. */
	Client client() {
		String clientId = "jcmb-it-" + UUID.randomUUID().toString().substring(0, 8);
		UUID id = this.platform.jdbc().sql("""
				insert into platform.integration_client (client_id, name, secret_hash, allowed_scopes, status)
				values (:clientId, 'JeCréeMaBoutique (essai)', :hash,
				  ARRAY['catalog:read','catalog:match','price:propose','stock:read','orders:read','orders:write']::varchar[],
				  'ACTIVE') returning id""")
			.param("clientId", clientId).param("hash", sha256Hex(UUID.randomUUID().toString())).query(UUID.class).single();
		this.platform.jdbc().sql("insert into platform.integration_client_redirect_uri (client_id, redirect_uri) values (:c, :u)")
			.param("c", id).param("u", REDIRECT_URI).update();
		this.clients.add(id);
		return new Client(id, clientId);
	}

	/** Liaison en attente de l'entreprise, pour la boutique donnée. */
	Link pending(Client client, UUID company, String shop, Set<String> scopes) {
		UUID request = this.platform.jdbc().sql("""
				insert into platform.integration_authorization_request (request_uri_hash, client_id, external_shop_id, shop_name,
				  redirect_uri, code_challenge, scopes, state, expires_at, decision, used_at)
				values (:hash, :client, :shop, :name, :redirect, 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM',
				  string_to_array(:scopes, ' ')::varchar[], repeat('s', 43), now() + interval '10 minutes', 'APPROVED', now())
				returning id""")
			.param("hash", sha256Hex(UUID.randomUUID().toString())).param("client", client.id()).param("shop", shop)
			.param("name", "Boutique " + shop).param("redirect", REDIRECT_URI).param("scopes", String.join(" ", new TreeSet<>(scopes)))
			.query(UUID.class).single();
		return this.registry.createPending(request, company, ADMIN, scopes);
	}

	/** Liaison active de l'entreprise, pour la boutique donnée. */
	Link active(Client client, UUID company, String shop, Set<String> scopes) {
		return this.registry.activate(pending(client, company, shop, scopes).id()).link();
	}

	/** Identifiant plateforme de l'entreprise de test historique (code {@code test}). */
	UUID legacyCompany() {
		return this.platform.jdbc().sql("select id from platform.company where code = 'test'").query(UUID.class).single();
	}

	void cleanUp() {
		for (UUID client : this.clients) {
			this.platform.jdbc().sql("""
					delete from platform.integration_authorization_code where request_id in
					  (select id from platform.integration_authorization_request where client_id = :c)""").param("c", client).update();
			this.platform.jdbc().sql("delete from platform.integration_link where client_id = :c").param("c", client).update();
			this.platform.jdbc().sql("delete from platform.integration_authorization_request where client_id = :c")
				.param("c", client).update();
			this.platform.jdbc().sql("delete from platform.integration_client where id = :c").param("c", client).update();
		}
		this.clients.clear();
	}

	private static String sha256Hex(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * @param id       {@code integration_client.id}
	 * @param clientId identifiant public (claim {@code client_id} des jetons)
	 */
	record Client(UUID id, String clientId) {
	}

}
